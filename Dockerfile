FROM gradle:8.11.1-jdk21 AS build
WORKDIR /src
COPY backend ./backend
RUN gradle --no-daemon -p backend installDist

FROM eclipse-temurin:21-jre-jammy
ENV PORT=8080 \
    VIDTUBE_DATA=/var/lib/vidtube \
    PATH="/opt/venv/bin:${PATH}"
RUN apt-get -o Acquire::Retries=5 -o Acquire::http::Pipeline-Depth=0 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends ffmpeg python3 python3-venv ca-certificates \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --upgrade yt-dlp \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/vidtube
COPY --from=build /src/backend/build/install/backend ./
RUN mkdir -p /var/lib/vidtube && chown -R 10001:10001 /opt/vidtube /var/lib/vidtube
USER 10001:10001
EXPOSE 8080
VOLUME ["/var/lib/vidtube"]
ENTRYPOINT ["/opt/vidtube/bin/backend"]
