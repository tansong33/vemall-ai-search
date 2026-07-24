#!/usr/bin/env python3
"""Fine-tune the product-search NER model.

    python scripts/train.py --config configs/train_base.yaml
    python scripts/train.py --config configs/train_base.yaml --set train.epochs=3
    python scripts/train.py --config configs/train_base.yaml --resume artifacts/ner-v1/last

Model selection is entity-level micro-F1 on the validation set — never token accuracy,
and never loss.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path
from typing import Any, Dict, List

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import torch
from torch.utils.data import DataLoader
from transformers import AutoTokenizer, get_cosine_schedule_with_warmup, get_linear_schedule_with_warmup

from nerkit.alignment import decode_spans
from nerkit.config import Config
from nerkit.dataset import NerJsonlDataset, compute_class_weights, make_collate_fn
from nerkit.io_utils import build_manifest, file_digest, write_json
from nerkit.labels import LabelScheme
from nerkit.metrics import evaluate_spans
from nerkit.model import ProductNerModel, parameter_groups
from nerkit.seeding import set_seed


def resolve_device(requested: str) -> torch.device:
    if requested != "auto":
        return torch.device(requested)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def resolve_amp(mode: str, device: torch.device) -> str:
    if device.type != "cuda" or mode == "no":
        return "no"
    if mode == "auto":
        return "bf16" if torch.cuda.is_bf16_supported() else "fp16"
    return mode


@torch.no_grad()
def run_eval(model, loader, scheme: LabelScheme, device, dump_errors: str = "") -> Dict[str, Any]:
    model.eval()
    gold_docs: List[List] = []
    pred_docs: List[List] = []
    texts: List[str] = []
    total_loss, n_batches = 0.0, 0
    for batch in loader:
        input_ids = batch["input_ids"].to(device)
        attn = batch["attention_mask"].to(device)
        labels = batch["labels"].to(device)
        out = model(input_ids, attn, labels=labels)
        total_loss += float(out["loss"])
        n_batches += 1
        tag_ids, conf = model.predict_tags(input_ids, attn)
        tag_ids, conf = tag_ids.cpu().tolist(), conf.cpu().tolist()
        for i, meta in enumerate(batch["meta"]):
            n = len(meta["offset_mapping"])
            spans = decode_spans(
                meta["text"], meta["offset_mapping"], meta["special_tokens_mask"],
                tag_ids[i][:n], scheme, token_confidence=conf[i][:n],
            )
            pred_docs.append([(s.start, s.end, s.label) for s in spans])
            gold_docs.append([tuple(g) for g in meta["gold"]])
            texts.append(meta["text"])
    result = evaluate_spans(gold_docs, pred_docs, scheme.entity_labels, texts)
    result["eval_loss"] = total_loss / max(n_batches, 1)
    if dump_errors:
        write_json(
            dump_errors,
            [
                {"text": c.text, "gold": c.gold, "pred": c.pred, "kinds": c.kinds}
                for c in result["error_cases"][:500]
            ],
        )
    result.pop("error_cases", None)
    model.train()
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--set", action="append", default=[], help="section.key=value override")
    ap.add_argument("--resume", default="")
    args = ap.parse_args()

    overrides: Dict[str, Any] = {}
    for item in args.set:
        key, _, value = item.partition("=")
        try:
            parsed: Any = json.loads(value)
        except json.JSONDecodeError:
            parsed = value
        overrides[key] = parsed
    cfg = Config.load(args.config, overrides)
    if args.resume:
        cfg.train.resume_from = args.resume

    set_seed(cfg.train.seed)
    device = resolve_device(cfg.train.device)
    amp_mode = resolve_amp(cfg.train.mixed_precision, device)
    out_dir = Path(cfg.train.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    scheme = LabelScheme(cfg.model.entity_labels, cfg.model.scheme)
    tokenizer = AutoTokenizer.from_pretrained(cfg.model.encoder, use_fast=True)
    if not tokenizer.is_fast:
        raise SystemExit(
            f"{cfg.model.encoder} has no fast tokenizer -> no offset_mapping -> "
            "character offsets cannot be produced. Pick a BERT-family model."
        )

    ds_kwargs = dict(
        tokenizer=tokenizer, scheme=scheme, max_length=cfg.data.max_length,
        boundary_policy=cfg.data.boundary_policy, lowercase=cfg.data.lowercase,
    )
    train_ds = NerJsonlDataset(cfg.data.train_file, **ds_kwargs)
    val_ds = NerJsonlDataset(cfg.data.validation_file, **ds_kwargs)
    collate = make_collate_fn(tokenizer.pad_token_id or 0)
    train_loader = DataLoader(
        train_ds, batch_size=cfg.train.train_batch_size, shuffle=True,
        collate_fn=collate, num_workers=cfg.train.num_workers, drop_last=False,
    )
    val_loader = DataLoader(
        val_ds, batch_size=cfg.train.eval_batch_size, shuffle=False,
        collate_fn=collate, num_workers=cfg.train.num_workers,
    )

    weights = compute_class_weights(
        train_ds.tag_counts(), scheme, cfg.train.class_weight, cfg.train.class_weight_cap
    )
    model = ProductNerModel(
        cfg.model.encoder, scheme, dropout=cfg.model.dropout, use_crf=cfg.model.use_crf,
        constrain_transitions=cfg.model.constrain_transitions,
        class_weights=weights.to(device) if weights is not None else None,
    ).to(device)

    optimizer = torch.optim.AdamW(parameter_groups(model, cfg.train))
    steps_per_epoch = max(1, len(train_loader))
    total_steps = steps_per_epoch * cfg.train.epochs
    warmup = int(total_steps * cfg.train.warmup_ratio)
    make_sched = (
        get_cosine_schedule_with_warmup if cfg.train.scheduler == "cosine"
        else get_linear_schedule_with_warmup
    )
    scheduler = make_sched(optimizer, num_warmup_steps=warmup, num_training_steps=total_steps)
    scaler = torch.cuda.amp.GradScaler(enabled=(amp_mode == "fp16"))

    state = {"epoch": 0, "global_step": 0, "best_metric": -1.0, "bad_epochs": 0, "history": []}
    if cfg.train.resume_from:
        ck = Path(cfg.train.resume_from)
        model_ck = ProductNerModel.load(ck, device=str(device))
        model.load_state_dict(model_ck.state_dict())
        if (ck / "optimizer.pt").exists():
            optimizer.load_state_dict(torch.load(ck / "optimizer.pt", map_location=device))
        if (ck / "scheduler.pt").exists():
            scheduler.load_state_dict(torch.load(ck / "scheduler.pt", map_location="cpu"))
        if (ck / "trainer_state.json").exists():
            state.update(json.loads((ck / "trainer_state.json").read_text(encoding="utf-8")))
        print(f"[resume] from {ck} at epoch {state['epoch']} step {state['global_step']}")

    manifest = build_manifest(
        {
            "config_file": args.config,
            "config": cfg.to_dict(),
            "config_fingerprint": cfg.fingerprint(),
            "device": str(device),
            "amp": amp_mode,
            "label_scheme": scheme.tags,
            "train_examples": len(train_ds),
            "validation_examples": len(val_ds),
            "train_alignment_stats": dict(train_ds.stats),
            "data_digests": {
                name: file_digest(path)
                for name, path in (
                    ("train", cfg.data.train_file), ("validation", cfg.data.validation_file)
                )
                if Path(path).exists()
            },
            "class_weights": weights.tolist() if weights is not None else None,
        }
    )
    write_json(out_dir / "training_manifest.json", manifest)
    print(f"[data] train={len(train_ds)} val={len(val_ds)} tags={scheme.num_tags} "
          f"device={device} amp={amp_mode}")
    print(f"[data] alignment issues in train: {dict(train_ds.stats)}")

    model.train()
    t0 = time.time()
    for epoch in range(state["epoch"], cfg.train.epochs):
        running = 0.0
        for step, batch in enumerate(train_loader, 1):
            input_ids = batch["input_ids"].to(device)
            attn = batch["attention_mask"].to(device)
            labels = batch["labels"].to(device)
            optimizer.zero_grad(set_to_none=True)
            if amp_mode in ("fp16", "bf16"):
                dtype = torch.float16 if amp_mode == "fp16" else torch.bfloat16
                with torch.autocast(device_type=device.type, dtype=dtype):
                    loss = model(input_ids, attn, labels=labels)["loss"]
                scaler.scale(loss).backward()
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(model.parameters(), cfg.train.max_grad_norm)
                scaler.step(optimizer)
                scaler.update()
            else:
                loss = model(input_ids, attn, labels=labels)["loss"]
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), cfg.train.max_grad_norm)
                optimizer.step()
            scheduler.step()
            state["global_step"] += 1
            running += float(loss)
            if cfg.train.logging_steps and state["global_step"] % cfg.train.logging_steps == 0:
                print(f"  epoch {epoch + 1} step {step}/{steps_per_epoch} "
                      f"loss={running / step:.4f} lr={scheduler.get_last_lr()[0]:.2e}")

        metrics = run_eval(model, val_loader, scheme, device,
                           dump_errors=str(out_dir / f"errors_epoch{epoch + 1}.json"))
        micro = metrics["micro"]
        score = micro["f1"] if cfg.train.early_stopping_metric == "micro_f1" else metrics["macro_f1"]
        state["epoch"] = epoch + 1
        state["history"].append(
            {
                "epoch": epoch + 1, "train_loss": running / steps_per_epoch,
                "eval_loss": metrics["eval_loss"], "micro_f1": micro["f1"],
                "macro_f1": metrics["macro_f1"],
                "per_label": {k: round(v["f1"], 4) for k, v in metrics["per_label"].items()},
            }
        )
        print(f"[epoch {epoch + 1}] train_loss={running / steps_per_epoch:.4f} "
              f"eval_loss={metrics['eval_loss']:.4f} micro_F1={micro['f1']:.4f} "
              f"macro_F1={metrics['macro_f1']:.4f} P={micro['precision']:.4f} R={micro['recall']:.4f}")
        for lab, m in sorted(metrics["per_label"].items()):
            print(f"    {lab:<10} P={m['precision']:.3f} R={m['recall']:.3f} "
                  f"F1={m['f1']:.3f} n={m['support']}")

        last_dir = out_dir / "last"
        model.save(last_dir, extra={"model_version": f"{out_dir.name}-e{epoch + 1}"})
        tokenizer.save_pretrained(last_dir)
        torch.save(optimizer.state_dict(), last_dir / "optimizer.pt")
        torch.save(scheduler.state_dict(), last_dir / "scheduler.pt")
        write_json(last_dir / "trainer_state.json", state)

        if score > state["best_metric"]:
            state["best_metric"] = score
            state["bad_epochs"] = 0
            best_dir = out_dir / "best"
            model.save(best_dir, extra={
                "model_version": f"{out_dir.name}-e{epoch + 1}",
                "validation_micro_f1": micro["f1"],
                "config_fingerprint": cfg.fingerprint(),
            })
            tokenizer.save_pretrained(best_dir)
            write_json(best_dir / "eval_metrics.json", metrics)
            print(f"  -> new best ({cfg.train.early_stopping_metric}={score:.4f}) saved to {best_dir}")
        else:
            state["bad_epochs"] += 1
            if state["bad_epochs"] >= cfg.train.early_stopping_patience:
                print(f"[early stop] no improvement for {state['bad_epochs']} epochs")
                break

    write_json(out_dir / "training_history.json", state["history"])
    print(f"[done] best {cfg.train.early_stopping_metric}={state['best_metric']:.4f} "
          f"in {time.time() - t0:.1f}s -> {out_dir / 'best'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
