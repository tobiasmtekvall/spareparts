FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY server ./server
# Railway sets PORT; the database and uploaded manuals live on the volume (RAILWAY_VOLUME_MOUNT_PATH)
CMD ["python", "server/app.py"]
