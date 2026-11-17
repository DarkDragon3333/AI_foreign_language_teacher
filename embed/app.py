import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel, Field
from fastembed import TextEmbedding

# fastembed не умеет multilingual-e5-small. Эта модель тоже многоязычная и тоже на 384 числа.
MODEL = os.environ.get("EMBED_MODEL", "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
model = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global model
    model = TextEmbedding(MODEL)
    # первый вызов скачивает веса в том и проверяет, что модель жива
    list(model.embed(["warmup"]))
    yield


app = FastAPI(lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=64)
    kind: str = "query"


@app.get("/health")
def health():
    return {"status": "up" if model is not None else "down", "model": MODEL, "dim": 384}


@app.post("/embed")
def embed(body: EmbedRequest):
    # Префиксы query:/passage: нужны только семейству e5. Этой модели они мешают.
    vectors = [v.tolist() for v in model.embed(body.texts)]
    return {"dim": len(vectors[0]), "vectors": vectors}
