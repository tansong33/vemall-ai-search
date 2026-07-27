#!/usr/bin/env python3
"""将 ModelScope RaNER Transformer-CRF 导出为 Java 可执行的 ONNX 制品。"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Dict


DEFAULT_MODEL_ID = (
    "iic/nlp_raner_named-entity-recognition_chinese-base-ecom-50cls"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    parser.add_argument("--revision", default="master")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--max-length", default=64, type=int)
    parser.add_argument("--opset", default=13, type=int)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def export(args: argparse.Namespace) -> Dict[str, str]:
    import torch
    from modelscope.hub.snapshot_download import snapshot_download
    from modelscope.models import Model
    from modelscope.preprocessors import Preprocessor

    model_dir = Path(
        snapshot_download(args.model_id, revision=args.revision)
        if not Path(args.model_id).is_dir()
        else args.model_id
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)

    raner = Model.from_pretrained(str(model_dir), device="cpu")
    raner.eval()

    class RaNerEmissionWrapper(torch.nn.Module):
        """导出 StructBERT emission；Java 端使用同一组 CRF 参数解码。"""

        def __init__(self, model):
            super().__init__()
            self.raner = model

        def forward(
            self,
            input_ids,
            attention_mask,
            token_type_ids,
        ):
            features = self.raner.encoder(
                input_ids=input_ids,
                attention_mask=attention_mask,
                token_type_ids=token_type_ids,
                return_dict=True,
            )
            return self.raner.head.linear(features.last_hidden_state)

    preprocessor = Preprocessor.from_pretrained(
        str(model_dir),
        max_length=args.max_length,
        return_text=False,
    )
    sample = preprocessor("eh 摇滚狗苹果15大疆御3")
    input_ids = sample["input_ids"].long()
    attention_mask = sample["attention_mask"].long()
    token_type_ids = torch.zeros_like(input_ids)

    output_path = args.output_dir / "model.onnx"
    wrapper = RaNerEmissionWrapper(raner).eval()
    with torch.no_grad():
        expected_predictions = wrapper(
            input_ids, attention_mask, token_type_ids
        ).cpu().numpy()
        torch.onnx.export(
            wrapper,
            (input_ids, attention_mask, token_type_ids),
            str(output_path),
            input_names=[
                "input_ids",
                "attention_mask",
                "token_type_ids",
            ],
            output_names=["logits"],
            opset_version=args.opset,
            do_constant_folding=True,
        )

    # Treat export validation as a release gate. A loadable ONNX file is not
    # sufficient: a previously traced graph passed onnx.checker while having
    # discarded every encoder input and all model weights.
    import onnx
    import onnxruntime as ort

    onnx.checker.check_model(str(output_path))
    session = ort.InferenceSession(
        str(output_path), providers=["CPUExecutionProvider"]
    )
    actual_inputs = {value.name for value in session.get_inputs()}
    required_inputs = {
        "input_ids",
        "attention_mask",
        "token_type_ids",
    }
    missing_inputs = required_inputs - actual_inputs
    if missing_inputs:
        raise RuntimeError(
            "ONNX export discarded required inputs: "
            + ", ".join(sorted(missing_inputs))
        )
    actual_predictions = session.run(
        ["logits"],
        {
            "input_ids": input_ids.cpu().numpy(),
            "attention_mask": attention_mask.cpu().numpy(),
            "token_type_ids": token_type_ids.cpu().numpy(),
        },
    )[0]
    if actual_predictions.shape != expected_predictions.shape:
        raise RuntimeError(
            "ONNX output shape differs from eager output: "
            f"{actual_predictions.shape} != {expected_predictions.shape}"
        )
    if not torch.from_numpy(actual_predictions).allclose(
        torch.from_numpy(expected_predictions), rtol=1e-3, atol=1e-4
    ):
        raise RuntimeError("ONNX logits differ from eager RaNER output")

    crf = raner.head.crf
    crf_path = args.output_dir / "crf.json"
    crf_path.write_text(
        json.dumps(
            {
                "startTransitions": crf.start_transitions.detach().cpu().tolist(),
                "endTransitions": crf.end_transitions.detach().cpu().tolist(),
                "transitions": crf.transitions.detach().cpu().tolist(),
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    copied = {}
    for name in (
        "vocab.txt",
        "config.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
    ):
        source = model_dir / name
        if source.is_file():
            target = args.output_dir / name
            shutil.copy2(source, target)
            copied[name] = sha256(target)

    metadata = {
        "sourceModel": args.model_id,
        "sourceRevision": args.revision,
        "maxLength": args.max_length,
        "opset": args.opset,
        "output": "logits",
        "includesCrfViterbi": False,
        "runtimeDecoder": "java-crf-viterbi",
        "modelSha256": sha256(output_path),
        "crfSha256": sha256(crf_path),
        "files": copied,
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return metadata


def main() -> None:
    args = parse_args()
    metadata = export(args)
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
