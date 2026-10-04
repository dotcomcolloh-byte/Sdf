# VidTube Hub

Kotlin/Compose Android app + Ktor backend (yt-dlp + ffmpeg). The backend Gradle project and Railway deployment files are at the repository root; the Android project is under `android/`.

## Railway deployment

Deploy from the repository root. `railway.json` explicitly selects the root `Dockerfile` and uses `/health` for the health check. Railway supplies `PORT`; the server binds to `0.0.0.0:$PORT`.

Set `VIDTUBE_DATA=/data` and attach a Railway Volume mounted at `/data` to preserve downloads, jobs, campaign assets, and caches across restarts. Configure optional campaign/payment variables from [`.env.example`](.env.example) as Railway service variables; do not commit a real `.env` file. Paystack's webhook path is `/api/paystack/webhook`.

## Local development

Backend requires JDK 21, `yt-dlp`, and `ffmpeg` on `PATH` (Deno is recommended as yt-dlp's JavaScript runtime).

```bash
./scripts/setup-tools.sh        # yt-dlp + ffmpeg (keep yt-dlp updated: pip install -U yt-dlp)
./scripts/run-backend.sh        # http://0.0.0.0:8080   (needs Java 21)
./gradlew test installDist
./gradlew -p android :app:assembleDebug
```
Set the deployed backend at Android build time with `./gradlew -p android :app:assembleRelease -PapiBaseUrl=https://api.yourdomain.com`. The APK is written under `android/app/build/outputs/apk/`.

## How it works
- `GET /api/videos?q&page&size` paged search (6h memory + disk cache, stale fallback) -> app infinite scroll.
- `GET /api/formats/{id}` real qualities/sizes -> download dialog (video 144p..best, audio MP3 64-320k).
- `GET /api/stream/{id}?quality` -> completed server download | HLS for Auto | Range-capable progressive proxy for an explicit quality (or HLS fallback).
- **Chunked playback:** `GET /api/stream/{id}` starts ffmpeg (video copied, audio -> AAC) writing 3s fMP4 HLS segments from yt-dlp's separate video/audio URLs; the app plays as soon as 2 segments exist (~2-4s) while ffmpeg keeps running ahead. Finished folders stay as the server cache (`VIDTUBE_HLS_GB`, default 15).
- `GET /api/proxy/{id}`, `GET /api/file/{name}` both support HTTP Range (seek, resume).
- `POST /api/download` -> server job (yt-dlp -c, real % progress, persisted in jobs.json, auto-resumed on restart).
- App `DownloadWorker` (WorkManager, foreground service) pulls the finished file with Range into app storage; on network loss it waits for connectivity and continues from the last byte.
- ExoPlayer: 1.5s start threshold, 120s read-ahead, 1 GB disk cache, next-video prefetch, auto re-prepare after connection loss.
- Only use with public videos you're permitted to download.

YouTube extraction prefers yt-dlp's token-free `web_embedded` client and retries with the documented token-free `android_vr` client for eligible challenge failures. `android_vr` does not support made-for-kids videos. Age/region restrictions, rate limits, or YouTube bot checks can still make videos unavailable; broader access may require a separately configured PO-token provider or authentication.

## Campaigns / ads
Settings → **Create campaigns** → Google sign-in → pricing, ad form, upload, Paystack checkout.
- **Env** (see `.env.example`): `GOOGLE_CLIENT_IDS`, `SESSION_SECRET`, `PAYSTACK_SECRET_KEY`, `PAYSTACK_CURRENCY`, `PUBLIC_BASE_URL`.
  Build the app with `-PgoogleWebClientId=<web client id>` (same value as in `GOOGLE_CLIENT_IDS`) and add your Android client (package + SHA-1) in Google Cloud.
- **Paystack:** set the webhook URL to `https://<api>/api/paystack/webhook`. Ads only go live after the server verifies the transaction with Paystack (exact amount + currency); the webhook and the client are never trusted on their own.
- **Pricing** (server-side, `Ads.kt`): 1-2d $1.00/day 1,500 views/day · 3-6d $0.90 · 7-13d $0.80 · 14-29d $0.70 · 30-59d $0.60 · 60-90d $0.50 (views/day rise 1,600 → 2,700).
- **Ad rules:** video >= 30 s (checked in the app AND by ffprobe on the server), <= 180 s, transcoded to H.264/AAC. Title + https link shown to users.
- **Lifetime:** ends at the chosen max days or when views are delivered. Delivery is paced across the campaign.
- **View =** ad video played >= 5 s, proven by a signed single-use token + real elapsed time; 1 billable view per device per ad per 30 min; owners' own views don't count.
- **Placements:** small "Sponsored" cards after every 5 videos in Home; download gate (watch >= 15 s -> single-use server pass, `POST /api/download` returns 403 `ad_required` without it); mid-rolls at ~50% and 20 s before the end for videos >= 10 min (3 breaks >= 40 min, 1 near the end for >= 2 min).
