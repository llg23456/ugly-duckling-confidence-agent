ARG PYTHON_IMAGE=public.ecr.aws/docker/library/python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df
FROM ${PYTHON_IMAGE}
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1 TZ=Asia/Shanghai
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates fonts-noto-cjk tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 duck \
    && useradd --uid 10001 --gid duck --no-create-home duck
WORKDIR /srv/app
COPY deploy/cloud-test/requirements.lock ./requirements.txt
RUN pip install --no-cache-dir --index-url https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt
COPY server/app ./app
COPY server/tests ./tests
COPY deploy/cloud-test/start.sh ./start.sh
COPY deploy/cloud-test/backup.py ./backup.py
USER 10001:10001
ENTRYPOINT ["sh", "/srv/app/start.sh"]
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--limit-concurrency", "16"]
