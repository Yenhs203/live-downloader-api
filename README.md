# VH MEDIA LIVE DOWNLOADER (Backend)

Spring Boot service that records livestreams from **direct HTTP/HTTPS stream URLs** (FLV, HLS, and similar CDN endpoints), saves them as MPEG-TS, remuxes to MP4, and exposes progress over SSE.

> **Important:** This application does **not** retrieve TikTok (or any platform) stream URLs automatically.  
> **The user supplies a direct stream URL they are authorized to access.** The backend only probes and records that URL.

## Requirements

| Dependency | Notes |
|---|---|
| **Java 21** | JDK 21+ |
| **Maven 3.9+** | Or use the included Maven Wrapper (`mvnw` / `mvnw.cmd`) |
| **PostgreSQL 16+** | Local install or Docker Compose |
| **FFmpeg** | On `PATH`, or set `FFMPEG_PATH` |
| **FFprobe** | On `PATH`, or set `FFPROBE_PATH` |

## Windows setup

1. Install **Eclipse Temurin JDK 21** (or another OpenJDK 21 distribution).
2. Install **Maven**, or rely on `.\mvnw.cmd` in this repository.
3. Install **FFmpeg** (includes `ffmpeg` and `ffprobe`) and add the `bin` folder to your user `PATH`.
4. Install **Docker Desktop** (recommended for PostgreSQL), **or** use a local PostgreSQL instance.
5. Clone this repository and open a PowerShell terminal in the project root.

### Verify FFmpeg / FFprobe

```powershell
ffmpeg -version
ffprobe -version
```

Both commands must print version information without “not recognized”.

### Start PostgreSQL (Docker)

```powershell
docker compose up -d
```

Default Compose credentials:

| Setting | Value |
|---|---|
| Database | `live_downloader` |
| Username | `postgres` |
| Password | `postgres` |
| Port | `5432` |

Data is persisted in the Docker volume `live_downloader_pgdata`.

Check health:

```powershell
docker compose ps
```

> If a **local** PostgreSQL is already bound to port `5432`, either stop it or point the app at Docker via a different host/port. Credentials must match whatever instance you use (`DB_USERNAME` / `DB_PASSWORD`).

## Environment variables

| Variable | Default (dev) | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | Spring profile (`dev` / `prod`) |
| `SERVER_PORT` | `8080` (base) / `8081` (dev) | HTTP port |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `live_downloader` | Database name |
| `DB_USERNAME` | `postgres` (dev) | Database user — **required in prod** |
| `DB_PASSWORD` | `postgres` (dev) | Database password — **required in prod** |
| `FFMPEG_PATH` | `ffmpeg` | Path or command name for FFmpeg |
| `FFPROBE_PATH` | `ffprobe` | Path or command name for FFprobe |
| `RECORDINGS_DIRECTORY` | `./recordings` (base) / `./recordings-dev` (dev) | Output directory for `.ts` / `.mp4` |
| `MAX_CONCURRENT_RECORDINGS` | `3` (base) / `2` (dev) | Max simultaneous FFmpeg recordings |
| `PROBE_TIMEOUT_SECONDS` | `30` | ffprobe timeout |
| `STOP_TIMEOUT_SECONDS` | `15` | Graceful FFmpeg stop wait |
| `DELETE_TEMP_AFTER_REMUX` | `true` | Delete intermediate `.ts` after successful remux |
| `PROGRESS_PERSIST_INTERVAL_SECONDS` | `3` | Throttle DB progress writes |
| `SSE_TIMEOUT_SECONDS` | `1800` | SSE connection timeout |
| `BLOCK_PRIVATE_STREAM_ADDRESSES` | `false` (dev) / `true` (prod) | Optional SSRF protection |
| `APP_CORS_ALLOWED_ORIGINS` | (required in prod) | Comma-separated UI origins — never `*` |
| `MIN_FREE_DISK_BYTES` | `512MiB` base / `1GiB` prod | Refuse start/record below free space |
| `DISK_SPACE_THRESHOLD_BYTES` | same order | Actuator diskspace indicator threshold |
| `VALIDATE_MEDIA_EXECUTABLES` | `false` / `true` (prod) | Fail boot if ffmpeg/ffprobe missing |
| `HTTP_BROWSER_HEADERS_ENABLED` | `true` | Send browser User-Agent + Referer on ffmpeg/ffprobe HTTP(S) |
| `HTTP_USER_AGENT` | Chrome-like UA | Value for ffmpeg/ffprobe `-user_agent` |
| `HTTP_REFERER` | `https://www.tiktok.com/` | Referer sent via ffmpeg/ffprobe `-headers` |
| `SECURITY_HEADERS_ENABLED` | `true` | Baseline security response headers |
| `SHUTDOWN_TIMEOUT` | `30s` / `45s` (prod) | Graceful Spring shutdown phase |

Production never ships hardcoded database credentials. Set `DB_USERNAME` and `DB_PASSWORD` via the environment when using the `prod` profile.

For production deployment (storage, Actuator, graceful shutdown, CORS, host FFmpeg), see **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** and **[.env.example](.env.example)**.

## How to run

From the project root (PowerShell):

```powershell
# Ensure PostgreSQL is up
docker compose up -d

# Run with the Maven Wrapper (recommended)
.\mvnw.cmd spring-boot:run
```

Or with a system Maven install:

```powershell
mvn spring-boot:run
```

The API listens on `http://localhost:8081` with the `dev` profile (default), or `http://localhost:8080` otherwise. Override with `SERVER_PORT`.

Health check:

```http
GET /api/v1/health
```

## API overview

Base URL: `http://localhost:8081` (dev)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/health` | Liveness |
| `POST` | `/api/v1/streams/probe` | Probe a stream URL (codecs, resolution, audio/video) |
| `POST` | `/api/v1/recordings` | Create job, probe, start recording (`202 Accepted`) |
| `GET` | `/api/v1/recordings` | List jobs (`page`, `size`, optional `status`) |
| `GET` | `/api/v1/recordings/{id}` | Job detail |
| `POST` | `/api/v1/recordings/{id}/stop` | Request graceful stop |
| `DELETE` | `/api/v1/recordings/{id}` | Soft-delete completed/failed/interrupted jobs + files |
| `GET` | `/api/v1/recordings/{id}/file` | Download final MP4 (`COMPLETED` only) |
| `GET` | `/api/v1/recordings/{id}/events` | SSE progress / status events |

### Example: probe

```http
POST /api/v1/streams/probe
Content-Type: application/json

{
  "url": "https://cdn.example.com/live/stream.flv?token=..."
}
```

### Example: start recording

```http
POST /api/v1/recordings
Content-Type: application/json

{
  "streamUrl": "https://cdn.example.com/live/stream.flv?token=..."
}
```

### SSE events

`GET /api/v1/recordings/{id}/events` (`Content-Type: text/event-stream`)

| Event | Meaning |
|---|---|
| `recording.started` | Job entered `RECORDING` |
| `recording.progress` | Progress tick (duration, bytes, fps, speed, bitrate) |
| `recording.stopping` | Graceful stop requested |
| `recording.remuxing` | Remux TS → MP4 in progress |
| `recording.completed` | Final MP4 ready |
| `recording.failed` | Terminal failure / interrupted |

On subscribe, the server immediately sends a snapshot of the current job state.

Errors use a consistent JSON body (`timestamp`, `status`, `code`, `message`, `path`). Sensitive URL query parameters are never returned to clients.

## Storage directory

| Profile | Default directory |
|---|---|
| `dev` | `./recordings-dev` |
| base / override | `RECORDINGS_DIRECTORY` (default `./recordings`) |

Per job, the server generates a safe basename (never taken from user input), for example:

```text
recordings-dev/
  live_20260811_143000_a1b2c3d4.ts    # intermediate MPEG-TS
  live_20260811_143000_a1b2c3d4.mp4   # final MP4 after remux
```

Paths are confined to the configured recordings root (path traversal is rejected).

## Job statuses

| Status | Meaning |
|---|---|
| `CREATED` | Job row created |
| `PROBING` | ffprobe in progress |
| `READY` | Probe OK, about to start FFmpeg |
| `RECORDING` | FFmpeg recording to `.ts` |
| `STOPPING` | Graceful stop requested |
| `REMUXING` | Remux `.ts` → `.mp4` |
| `COMPLETED` | Final MP4 available |
| `FAILED` | Probe / record / remux failure |
| `INTERRUPTED` | Process lost (e.g. app restart while active) |
| `DELETED` | Soft-deleted (hidden from default list) |

## Recording lifecycle

```text
User supplies authorized direct stream URL
        │
        ▼
   Validate URL (http/https only)
        │
        ▼
   Probe with ffprobe ──► reject if no video
        │
        ▼
   Start FFmpeg (copy → MPEG-TS)     status: RECORDING
        │
        ├── SSE progress events
        │
   Stop (user) or natural stream end
        │
        ▼
   Remux TS → MP4 (stream copy)      status: REMUXING
        │
        ▼
   COMPLETED  → download via /file
```

On application restart, jobs left in `RECORDING` / `STOPPING` are marked `INTERRUPTED`.  
`REMUXING` jobs become `COMPLETED` only if a valid non-empty MP4 already exists; otherwise `INTERRUPTED`.  
**Old stream URLs are never auto-restarted** (tokens may have expired).

## Troubleshooting

### `ffmpeg` / `ffprobe` not found

- Run `ffmpeg -version` and `ffprobe -version` in the same terminal you use to start the app.
- Add FFmpeg’s `bin` directory to Windows `PATH`, then restart the IDE/terminal.
- Or set absolute paths:

```powershell
$env:FFMPEG_PATH = "C:\ffmpeg\bin\ffmpeg.exe"
$env:FFPROBE_PATH = "C:\ffmpeg\bin\ffprobe.exe"
```

### Expired / invalid stream URL

- The backend does not refresh CDN or platform tokens.
- Obtain a **fresh direct stream URL** you are authorized to use, then probe/record again.
- Query tokens in URLs are redacted in logs and API error messages.

### No audio

- Recording still proceeds if a **video** track is present.
- Streams with **no video** are rejected (`STREAM_PROBE_FAILED` / no-video error).
- Missing audio is allowed; the remux maps optional audio with `-map 0:a:0?`.

### Probe timeout

- Response: HTTP `504` with code `STREAM_PROBE_TIMEOUT`.
- Causes: dead URL, network blocks, CDN geo-restrictions, or a slow origin.
- Increase `PROBE_TIMEOUT_SECONDS` if needed, and verify the URL in a player first.

### PostgreSQL connection failed

- Confirm the database is running: `docker compose ps` or check your local Postgres service.
- Verify credentials match (`postgres` / `postgres` for Compose + `dev` profile).
- Common Windows issue: a **local** Postgres already on port `5432` with a different password — either stop it, change the password, or set `DB_PASSWORD` to match.
- Ensure database `live_downloader` exists (Compose creates it automatically).
- Flyway runs on startup; schema errors usually mean a wrong database or failed migration.

## License / usage

Use only with stream URLs you are authorized to access. Operators are responsible for compliance with applicable laws and platform terms.
