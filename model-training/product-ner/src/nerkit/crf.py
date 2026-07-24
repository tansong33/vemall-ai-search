"""Vendored linear-chain CRF.

Rationale for vendoring instead of adding a dependency: the de-facto library
``pytorch-crf`` has had no release since 0.7.2 (2019) and pins nothing about modern
PyTorch, so taking a hard dependency on it for a production service is a liability.
The implementation below is ~150 lines of standard log-space forward / Viterbi /
forward-backward, is unit tested (``tests/test_crf.py``) against a brute-force
enumeration, and has no dependency beyond torch.
"""
from __future__ import annotations

from typing import List, Optional, Sequence

import torch
import torch.nn as nn

NEG = -1e4  # finite "impossible" score; -inf would produce NaNs in backward


class CRF(nn.Module):
    def __init__(self, num_tags: int, batch_first: bool = True) -> None:
        super().__init__()
        self.num_tags = num_tags
        self.batch_first = batch_first
        self.start_transitions = nn.Parameter(torch.empty(num_tags))
        self.end_transitions = nn.Parameter(torch.empty(num_tags))
        self.transitions = nn.Parameter(torch.empty(num_tags, num_tags))
        self.register_buffer("constraint_mask", torch.zeros(num_tags, num_tags, dtype=torch.bool))
        self.reset_parameters()

    def reset_parameters(self) -> None:
        for p in (self.start_transitions, self.end_transitions, self.transitions):
            nn.init.uniform_(p, -0.1, 0.1)

    def apply_constraints(self, invalid: Sequence[tuple[int, int]]) -> None:
        """Forbid transitions that cannot occur in a well-formed BIO/BIOES sequence."""
        mask = torch.zeros(self.num_tags, self.num_tags, dtype=torch.bool)
        for i, j in invalid:
            mask[i, j] = True
        self.constraint_mask = mask.to(self.transitions.device)

    def _transitions(self) -> torch.Tensor:
        return self.transitions.masked_fill(self.constraint_mask, NEG)

    @staticmethod
    def _prep(emissions: torch.Tensor, tags, mask, batch_first: bool):
        if batch_first:
            emissions = emissions.transpose(0, 1)
            if tags is not None:
                tags = tags.transpose(0, 1)
            if mask is not None:
                mask = mask.transpose(0, 1)
        return emissions, tags, mask

    def forward(
        self,
        emissions: torch.Tensor,
        tags: torch.Tensor,
        mask: Optional[torch.Tensor] = None,
        reduction: str = "mean",
    ) -> torch.Tensor:
        """Negative log-likelihood of ``tags`` given ``emissions``."""
        emissions, tags, mask = self._prep(emissions, tags, mask, self.batch_first)
        if mask is None:
            mask = torch.ones(emissions.shape[:2], dtype=torch.bool, device=emissions.device)
        mask = mask.bool()
        numerator = self._score(emissions, tags, mask)
        denominator = self._log_partition(emissions, mask)
        nll = denominator - numerator
        if reduction == "none":
            return nll
        if reduction == "sum":
            return nll.sum()
        if reduction == "token_mean":
            return nll.sum() / mask.sum().clamp(min=1)
        return nll.mean()

    def _score(self, emissions, tags, mask) -> torch.Tensor:
        seq_len, batch = tags.shape
        trans = self._transitions()
        score = self.start_transitions[tags[0]] + emissions[0].gather(1, tags[0].unsqueeze(1)).squeeze(1)
        for i in range(1, seq_len):
            step = trans[tags[i - 1], tags[i]] + emissions[i].gather(1, tags[i].unsqueeze(1)).squeeze(1)
            score = score + step * mask[i]
        last_idx = mask.long().sum(0) - 1
        last_tags = tags.gather(0, last_idx.unsqueeze(0)).squeeze(0)
        return score + self.end_transitions[last_tags]

    def _log_partition(self, emissions, mask) -> torch.Tensor:
        seq_len = emissions.shape[0]
        trans = self._transitions()
        alpha = self.start_transitions + emissions[0]
        for i in range(1, seq_len):
            broadcast = alpha.unsqueeze(2) + trans.unsqueeze(0) + emissions[i].unsqueeze(1)
            nxt = torch.logsumexp(broadcast, dim=1)
            alpha = torch.where(mask[i].unsqueeze(1), nxt, alpha)
        return torch.logsumexp(alpha + self.end_transitions, dim=1)

    @torch.no_grad()
    def decode(self, emissions: torch.Tensor, mask: Optional[torch.Tensor] = None) -> List[List[int]]:
        """Viterbi best path per sequence."""
        emissions, _, mask = self._prep(emissions, None, mask, self.batch_first)
        if mask is None:
            mask = torch.ones(emissions.shape[:2], dtype=torch.bool, device=emissions.device)
        mask = mask.bool()
        seq_len, batch, _ = emissions.shape
        trans = self._transitions()
        score = self.start_transitions + emissions[0]
        history: List[torch.Tensor] = []
        for i in range(1, seq_len):
            broadcast = score.unsqueeze(2) + trans.unsqueeze(0) + emissions[i].unsqueeze(1)
            best, idx = broadcast.max(dim=1)
            score = torch.where(mask[i].unsqueeze(1), best, score)
            history.append(idx)
        score = score + self.end_transitions
        lengths = mask.long().sum(0)
        best_paths: List[List[int]] = []
        for b in range(batch):
            best_tag = int(score[b].argmax())
            path = [best_tag]
            for hist in reversed(history[: max(int(lengths[b]) - 1, 0)]):
                best_tag = int(hist[b][best_tag])
                path.append(best_tag)
            path.reverse()
            best_paths.append(path)
        return best_paths

    @torch.no_grad()
    def marginals(self, emissions: torch.Tensor, mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        """Per-token posterior P(tag | sequence) — used as the confidence the API returns.

        A Viterbi path score alone is a *sequence* score; the hybrid/fallback policy
        needs a per-entity probability, so we run forward-backward.
        """
        emissions, _, mask = self._prep(emissions, None, mask, self.batch_first)
        if mask is None:
            mask = torch.ones(emissions.shape[:2], dtype=torch.bool, device=emissions.device)
        mask = mask.bool()
        seq_len, batch, num_tags = emissions.shape
        trans = self._transitions()

        alphas = [self.start_transitions + emissions[0]]
        for i in range(1, seq_len):
            nxt = torch.logsumexp(
                alphas[-1].unsqueeze(2) + trans.unsqueeze(0) + emissions[i].unsqueeze(1), dim=1
            )
            alphas.append(torch.where(mask[i].unsqueeze(1), nxt, alphas[-1]))

        betas = [torch.zeros(batch, num_tags, device=emissions.device) + self.end_transitions]
        for i in range(seq_len - 1, 0, -1):
            prev = torch.logsumexp(
                trans.unsqueeze(0) + emissions[i].unsqueeze(1) + betas[-1].unsqueeze(1), dim=2
            )
            betas.append(torch.where(mask[i].unsqueeze(1), prev, betas[-1]))
        betas.reverse()

        log_z = torch.logsumexp(alphas[-1] + self.end_transitions, dim=1)
        out = torch.stack(
            [alphas[i] + betas[i] - log_z.unsqueeze(1) for i in range(seq_len)], dim=0
        ).exp()
        return out.transpose(0, 1) if self.batch_first else out
