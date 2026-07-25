import os
import unicodedata
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import List

from fastapi import FastAPI, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse


FILE_ROOT = Path(os.getenv("FILE_ROOT", "/data")).resolve()
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_BYTES", str(1024 * 1024 * 1024)))
CHUNK_SIZE = 1024 * 1024
INDEX_FILE = Path(__file__).with_name("index.html")
INVALID_FILENAME_CHARS = set('<>:"/\\|?*')

FILE_ROOT.mkdir(parents=True, exist_ok=True)

app = FastAPI(
    title="AI Search File Service",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


def safe_filename(raw_name: str) -> str:
    name = unicodedata.normalize("NFC", raw_name or "").strip()
    if (
        not name
        or name in {".", ".."}
        or name.startswith(".")
        or len(name) > 240
        or any(character in INVALID_FILENAME_CHARS or ord(character) < 32 for character in name)
        or Path(name).name != name
    ):
        raise HTTPException(status_code=400, detail="Invalid filename")
    return name


def destination_for(raw_name: str) -> Path:
    destination = (FILE_ROOT / safe_filename(raw_name)).resolve()
    if destination.parent != FILE_ROOT:
        raise HTTPException(status_code=400, detail="Invalid filename")
    return destination


@app.get("/")
def index() -> FileResponse:
    return FileResponse(INDEX_FILE, media_type="text/html; charset=utf-8")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "file-service"}


@app.get("/api/files")
def list_files() -> dict:
    files = []
    for path in sorted(FILE_ROOT.iterdir(), key=lambda item: item.name.casefold()):
        if not path.is_file() or path.name.startswith(".upload-"):
            continue
        stat = path.stat()
        files.append(
            {
                "name": path.name,
                "size": stat.st_size,
                "modifiedAt": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
            }
        )
    return {"files": files, "maxUploadBytes": MAX_UPLOAD_BYTES}


@app.post("/api/files", status_code=status.HTTP_201_CREATED)
async def upload_files(
    files: List[UploadFile] = File(...),
    overwrite: bool = Form(False),
) -> dict:
    uploaded = []
    for upload in files:
        destination = destination_for(upload.filename or "")
        if destination.exists() and not overwrite:
            raise HTTPException(status_code=409, detail=f"{destination.name} already exists")

        temporary = FILE_ROOT / f".upload-{uuid.uuid4().hex}"
        size = 0
        try:
            with temporary.open("xb") as target:
                while True:
                    chunk = await upload.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > MAX_UPLOAD_BYTES:
                        raise HTTPException(status_code=413, detail=f"{destination.name} is too large")
                    target.write(chunk)
            temporary.replace(destination)
        except Exception:
            temporary.unlink(missing_ok=True)
            raise
        finally:
            await upload.close()

        uploaded.append({"name": destination.name, "size": size})
    return {"uploaded": uploaded}


@app.get("/api/files/{filename}")
def download_file(filename: str) -> FileResponse:
    path = destination_for(filename)
    if not path.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(path, filename=path.name, media_type="application/octet-stream")
