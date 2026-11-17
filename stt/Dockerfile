FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# PyAV 19 убрал аргумент metadata_errors, а faster-whisper 1.1.1 его ещё передаёт.
RUN pip install --no-cache-dir fastapi==0.115.6 uvicorn==0.34.0 python-multipart==0.0.20 requests==2.32.3 "av>=14,<19" faster-whisper==1.1.1
COPY app.py .
ENV WHISPER_DOWNLOAD_ROOT=/models
EXPOSE 9001
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "9001"]
