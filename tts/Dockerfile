FROM python:3.12-slim

# espeak-ng нужен phonemizer, чтобы собрать английские фонемы. Видеокарта этому контейнеру не отдаётся.
RUN apt-get update && apt-get install -y --no-install-recommends espeak-ng \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN pip install --no-cache-dir fastapi==0.115.6 uvicorn==0.34.0 requests==2.32.3 kokoro-onnx==0.6.1
COPY app.py .
ENV KOKORO_DIR=/models
ENV KOKORO_VOICE=af_heart
EXPOSE 9002
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "9002"]
