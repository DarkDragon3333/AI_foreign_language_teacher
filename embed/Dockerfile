FROM python:3.12-slim

WORKDIR /app
RUN pip install --no-cache-dir fastapi==0.115.6 uvicorn==0.34.0 fastembed==0.6.1
COPY app.py .
ENV FASTEMBED_CACHE_PATH=/models
EXPOSE 9003
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "9003"]
