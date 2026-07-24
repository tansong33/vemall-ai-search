# Inference image. Training runs on the host/GPU box, not here.
FROM python:3.11-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    OMP_NUM_THREADS=1 \
    NER_TORCH_THREADS=1 \
    HF_HUB_OFFLINE=1

WORKDIR /app

# CPU-only torch keeps the image ~1.2GB instead of ~6GB with the CUDA wheels.
RUN pip install --no-cache-dir torch==2.13.0 --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN grep -v '^torch==' requirements.txt > /tmp/req.txt \
    && pip install --no-cache-dir -r /tmp/req.txt

COPY src/ ./src/
COPY service/ ./service/
COPY scripts/ ./scripts/
COPY configs/ ./configs/

# Model + dictionaries are mounted, never baked in: they version independently of code.
ENV NER_MODEL_DIR=/models/ner-v1/best \
    NER_DICT_FILES=/dicts/brand.tsv,/dicts/category.tsv \
    NER_MODE=hybrid \
    PYTHONPATH=/app/src:/app

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/ready', timeout=2).status==200 else 1)"

# 2 workers x 1 thread beats 1 worker x 2 threads for short-sequence CPU inference.
CMD ["uvicorn", "service.app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
