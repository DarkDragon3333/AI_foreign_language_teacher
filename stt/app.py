import os
import tempfile
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Query, UploadFile
from faster_whisper import WhisperModel

MODEL_NAME = os.environ.get("WHISPER_MODEL", "small")
COMPUTE_TYPE = os.environ.get("COMPUTE_TYPE", "int8")
DEVICE = os.environ.get("DEVICE", "cpu")
# пусто — язык определяется сам: в уроке бывает и английский, и короткая русская просьба о помощи
LANGUAGE = os.environ.get("STT_LANGUAGE", "").strip() or None
DOWNLOAD_ROOT = os.environ.get("WHISPER_DOWNLOAD_ROOT", "/models")

model = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global model
    model = WhisperModel(MODEL_NAME, device=DEVICE, compute_type=COMPUTE_TYPE, download_root=DOWNLOAD_ROOT)
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    return {
        "status": "up" if model is not None else "down",
        "model": MODEL_NAME,
        "device": DEVICE,
        "compute": COMPUTE_TYPE,
    }


def resolve_language(override: str | None) -> str | None:
    raw = (override or "").strip()
    if raw.lower() in ("", "auto"):
        raw = LANGUAGE or ""
    raw = raw.strip()
    return raw or None


@app.post("/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    language: str | None = Query(None),
    prompt: str | None = Query(None),
):
    data = await file.read()
    if not data:
        return {"text": "", "language": None}
    suffix = os.path.splitext(file.filename or "")[1] or ".webm"
    path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(data)
            path = tmp.name
        lang = resolve_language(language)
        hint = (prompt or "").strip()[:220] or None
        segments, info = model.transcribe(
            path,
            language=lang,
            initial_prompt=hint,
            vad_filter=True,
            beam_size=1,
            condition_on_previous_text=False,
        )
        text = " ".join(segment.text.strip() for segment in segments).strip()
        return {"text": text, "language": info.language}
    finally:
        if path and os.path.exists(path):
            os.remove(path)
