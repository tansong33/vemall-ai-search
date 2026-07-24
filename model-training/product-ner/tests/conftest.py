import string
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(ROOT))

from nerkit.labels import LabelScheme  # noqa: E402

LABELS = ["BRAND", "CATEGORY", "MODEL", "SPEC", "COLOR"]
SAMPLE_CHARS = "华为手机插座公牛小米电视黑色白色银色官方旗舰店家用不锈钢儿童保温杯红苹果水果胶带充电器包邮英寸孔米"


@pytest.fixture(scope="session")
def scheme():
    return LabelScheme(LABELS, "BIO")


@pytest.fixture(scope="session")
def bioes():
    return LabelScheme(LABELS, "BIOES")


@pytest.fixture(scope="session")
def tokenizer(tmp_path_factory):
    """A real fast WordPiece tokenizer built offline (no network, no model download)."""
    from transformers import BertTokenizerFast

    d = tmp_path_factory.mktemp("tok")
    chars = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]
    chars += sorted(set(SAMPLE_CHARS + string.ascii_lowercase + string.digits + "+-."))
    chars += ["##" + c for c in string.ascii_lowercase + string.digits]
    (d / "vocab.txt").write_text("\n".join(chars) + "\n", encoding="utf-8")
    return BertTokenizerFast(vocab_file=str(d / "vocab.txt"), do_lower_case=True)
