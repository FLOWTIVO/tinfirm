# The docs site (docs-site/README.md). The build context is docs-site/ alone.
#
#   docker build --target build docs-site    the strict build, as CI's docs job runs it
#   docker build docs-site                   the image that serves the built site
#
# In Docker, so that a CI runner needs no Python, as runner/ci.Dockerfile does for Rust.
# The strict build fails on a link to a page or an anchor that does not exist.
FROM python:3.15-slim-trixie@sha256:65a65fc7b30b06cb6df4930fd4650697cb23f4c2698ab81fc87e4b031bc36928 AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1
WORKDIR /site
# Every package and its hash are in the lock, so nothing outside it is installed.
COPY requirements.txt ./
RUN pip install --no-cache-dir --require-hashes --no-deps -r requirements.txt
COPY zensical.toml ./
COPY includes ./includes
COPY content ./content
RUN zensical build --strict --clean

# The runtime: the built site behind nginx, as the image's unprivileged user 101, on port
# 8080. The root filesystem can be read-only: nginx writes only its pid and temporary
# files, to /tmp.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine-slim@sha256:a8cb0f33d51fa107c96fd2bd3a71aaf564ec131e9d2bdf8700944f0ed8b170d7 AS runtime
ARG REVISION=unknown
LABEL org.opencontainers.image.title="tinfirm-docs" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.revision="${REVISION}"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /site/site /srv/docs
USER 101:101
EXPOSE 8080
