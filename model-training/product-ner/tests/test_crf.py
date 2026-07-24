import itertools

import torch

from nerkit.crf import CRF


def test_log_partition_matches_bruteforce():
    torch.manual_seed(0)
    crf = CRF(num_tags=4)
    emissions = torch.randn(1, 5, 4)
    mask = torch.ones(1, 5, dtype=torch.bool)
    total = []
    for path in itertools.product(range(4), repeat=5):
        tags = torch.tensor([path])
        score = -crf(emissions, tags, mask, reduction="none") + crf._log_partition(
            emissions.transpose(0, 1), mask.transpose(0, 1)
        )
        total.append(score)
    brute = torch.logsumexp(torch.stack(total), dim=0)
    ref = crf._log_partition(emissions.transpose(0, 1), mask.transpose(0, 1))
    assert torch.allclose(brute, ref, atol=1e-4)


def test_viterbi_is_the_argmax_path():
    torch.manual_seed(1)
    crf = CRF(num_tags=3)
    emissions = torch.randn(1, 4, 3)
    mask = torch.ones(1, 4, dtype=torch.bool)
    best_path, best_score = None, None
    for path in itertools.product(range(3), repeat=4):
        tags = torch.tensor([path])
        score = crf._score(emissions.transpose(0, 1), tags.transpose(0, 1), mask.transpose(0, 1))
        if best_score is None or score > best_score:
            best_score, best_path = score, list(path)
    assert crf.decode(emissions, mask)[0] == best_path


def test_marginals_are_probabilities():
    torch.manual_seed(2)
    crf = CRF(num_tags=5)
    emissions = torch.randn(2, 6, 5)
    mask = torch.ones(2, 6, dtype=torch.bool)
    marg = crf.marginals(emissions, mask)
    assert marg.shape == (2, 6, 5)
    assert torch.allclose(marg.sum(-1), torch.ones(2, 6), atol=1e-4)
    assert (marg >= 0).all() and (marg <= 1).all()


def test_constraints_forbid_illegal_transitions(scheme):
    crf = CRF(num_tags=scheme.num_tags)
    crf.apply_constraints(scheme.invalid_transitions())
    trans = crf._transitions()
    o, i_brand = scheme.tag_id("O"), scheme.tag_id("I-BRAND")
    assert trans[o, i_brand] < -1000


def test_masked_positions_do_not_change_loss():
    torch.manual_seed(3)
    crf = CRF(num_tags=3)
    emissions = torch.randn(1, 5, 3)
    tags = torch.zeros(1, 5, dtype=torch.long)
    mask = torch.tensor([[True, True, True, False, False]])
    loss_a = crf(emissions, tags, mask)
    emissions2 = emissions.clone()
    emissions2[0, 3:] = torch.randn(2, 3) * 100  # garbage behind the mask
    assert torch.allclose(loss_a, crf(emissions2, tags, mask), atol=1e-4)
