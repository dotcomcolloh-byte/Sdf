# Build the standalone backend distribution from the repository root.
FROM gradle:8.11.1-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle installDist --no-daemon -q

FROM eclipse-temurin:21-jre-jammy
RUN apt-get -o Acquire::Retries=5 -o Acquire::http::Pipeline-Depth=0 update \
 && apt-get -o Acquire::Retries=5 install -y --no-install-recommends ffmpeg python3 curl ca-certificates unzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL --retry 5 --retry-all-errors https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
 && chmod +x /usr/local/bin/yt-dlp \
 && curl -fsSL --retry 5 --retry-all-errors https://deno.land/install.sh -o /tmp/install-deno.sh \
 && DENO_INSTALL=/usr/local sh /tmp/install-deno.sh \
 && rm -f /tmp/install-deno.sh
COPY --from=build /src/build/install/vidtube-hub-backend /app
ENV VIDTUBE_DATA=/data
EXPOSE 8080
# The yt-dlp version is fixed by the release binary fetched at image build time.
# Log tool versions only; do not emit extractor stderr, URLs, or credentials.
CMD ["sh", "-c", "ytdlp_version=$(yt-dlp --version 2>/dev/null || echo unavailable); deno_version=$(deno --version 2>/dev/null | head -n 1 || echo unavailable); ffmpeg_version=$(ffmpeg -version 2>/dev/null | head -n 1 || echo unavailable); echo \"VidTube runtime: yt-dlp=$ytdlp_version deno=$deno_version ffmpeg=$ffmpeg_version\"; exec /app/bin/vidtube-hub-backend"]
