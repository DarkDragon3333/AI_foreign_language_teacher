import io
import os
import re
import threading
import wave
from contextlib import asynccontextmanager

import numpy as np
import requests
from fastapi import FastAPI
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel

MODEL_DIR = os.environ.get("KOKORO_DIR", "/models")
VOICE = os.environ.get("KOKORO_VOICE", "af_heart").strip() or "af_heart"
MODEL_NAME = "kokoro-v1.0.fp16.onnx"
VOICES_NAME = "voices-v1.0.bin"
RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/"
MAX_CHARS = 800

kokoro = None
lock = threading.Lock()


def download(name):
    path = os.path.join(MODEL_DIR, name)
    if os.path.isfile(path) and os.path.getsize(path) > 1_000_000:
        return path
    os.makedirs(MODEL_DIR, exist_ok=True)
    temporary = path + ".part"
    with requests.get(RELEASE + name, stream=True, timeout=(30, 300)) as response:
        response.raise_for_status()
        with open(temporary, "wb") as handle:
            for chunk in response.iter_content(1024 * 1024):
                if chunk:
                    handle.write(chunk)
    os.replace(temporary, path)
    return path


@asynccontextmanager
async def lifespan(app: FastAPI):
    global kokoro
    from kokoro_onnx import Kokoro

    kokoro = Kokoro(download(MODEL_NAME), download(VOICES_NAME))
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "up" if kokoro is not None else "down", "voice": VOICE, "device": "cpu"}


class SpeakRequest(BaseModel):
    text: str
    voice: str | None = None


def sentences(text):
    pieces = re.split(r"(?<=[.!?])\s+", text.strip())
    ready = []
    for piece in pieces:
        piece = piece.strip()
        while len(piece) > 400:
            ready.append(piece[:400])
            piece = piece[400:].strip()
        if piece:
            ready.append(piece)
    return ready


def wav_bytes(samples, sample_rate):
    audio = np.asarray(samples, dtype=np.float32).reshape(-1)
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(int(sample_rate))
        handle.writeframes(pcm.tobytes())
    return buffer.getvalue()


@app.post("/speak")
def speak(body: SpeakRequest):
    if kokoro is None:
        return JSONResponse({"error": "voice is still loading"}, status_code=503)
    text = " ".join(body.text.split())
    if not text:
        return JSONResponse({"error": "empty text"}, status_code=400)
    text = text[:MAX_CHARS]
    voice = (body.voice or VOICE).strip() or VOICE
    parts = []
    rate = 24000
    try:
        with lock:
            for piece in sentences(text):
                samples, rate = kokoro.create(piece, voice=voice, speed=1.0, lang="en-us")
                parts.append(np.asarray(samples, dtype=np.float32).reshape(-1))
    except Exception as error:
        return JSONResponse({"error": str(error)}, status_code=500)
    if not parts:
        return JSONResponse({"error": "empty text"}, status_code=400)
    return Response(content=wav_bytes(np.concatenate(parts), rate), media_type="audio/wav")
