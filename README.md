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
| **FFmpeg** | On `PATH`, or set `FFMPEG_PATH`. Editor export needs `libx264` + AAC. |
| **FFprobe** | On `PATH`, or set `FFPROBE_PATH`. Used for stream probe and editor source probe. |

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
| `MAX_CONCURRENT_EDITOR_EXPORTS` | `2` / `1` (dev) | Max simultaneous FFmpeg editor exports (HTTP 429 when exceeded). Alias: `EDITOR_MAX_CONCURRENT_EXPORTS` |
| `PROBE_TIMEOUT_SECONDS` | `30` | ffprobe timeout |
| `STOP_TIMEOUT_SECONDS` | `15` | Graceful FFmpeg stop wait |
| `DELETE_TEMP_AFTER_REMUX` | `true` | Delete intermediate `.ts` after successful remux |
| `PROGRESS_PERSIST_INTERVAL_SECONDS` | `3` | Throttle DB progress writes **and** editor INFO progress summaries (not per FFmpeg frame) |
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
| `EDITOR_IMAGE_SEGMENTS_ENABLED` | `false` | Phase 1B IMAGE upload + visual replace |
| `EDITOR_STORAGE_DIRECTORY` | `{recordings}/editor` / `./recordings-dev/editor` | Editor file root (`source/`, `assets/`, `exports/`) |
| `EDITOR_MAX_UPLOAD_BYTES` | `512MiB` | Max source MP4 upload |
| `EDITOR_MAX_IMAGE_UPLOAD_BYTES` | `20MiB` | Max IMAGE asset upload |
| `EDITOR_MAX_CONCURRENT_EXPORTS` | `2` / `1` (dev) | Deprecated alias for `MAX_CONCURRENT_EDITOR_EXPORTS` |
| `EDITOR_EXPORT_TIMEOUT_MINUTES` | `60` | FFmpeg export timeout |
| `EDITOR_MIN_SEGMENT_DURATION_MS` | `100` | Minimum clip length after split / boundary / trim. Alias: `EDITOR_MIN_SEGMENT_MILLIS` |
| `EDITOR_COVERAGE_EPSILON_MILLIS` | `50` | Slack (ms) for source-contiguous cuts and coverage |

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

Swagger UI (disabled in `prod`): `http://localhost:8081/swagger-ui/index.html`

OpenAPI groups: **Video Editor**, **Recordings**, **Stream Probe**, **Health**.

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
| `GET` | `/api/v1/editor/options` | Export presets (fps, resolution, codec, quality). `keepOriginalAudio` is always true |
| `POST` | `/api/v1/editor/projects` | Create editor project from uploaded MP4 (`multipart`: `file`, optional `name`) |
| `POST` | `/api/v1/editor/projects/from-recording/{recordingId}` | Create editor project from a `COMPLETED` recording |
| `GET` | `/api/v1/editor/projects` | List projects (`page`, `size`) |
| `GET` | `/api/v1/editor/projects/{id}` | Project detail (`sourceDurationMillis`, `outputDurationMillis`, `timelineVersion`, segment `visualDurationMillis`, `canMergeNext`, `canResizeRightBoundary`, `canResizeLeftBoundary`) |
| `POST` | `/api/v1/editor/projects/{id}/segments/{segmentId}/split` | Split a VIDEO segment at `atMillis` |
| `POST` | `/api/v1/editor/projects/{id}/segments/{segmentId}/merge-next` | Undo split: merge with the next timeline neighbor (source-contiguous VIDEO, same rate) |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/boundary` | Move the shared source cut between this clip and the next (only when `canResizeRightBoundary`) |
| `PUT` | `/api/v1/editor/projects/{id}/output-range` | Canonical project trim: crop current visual output `{ startMillis, endMillis }` (e.g. 27.167s → 25.000s) |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/trim` | Trim first-clip left / last-clip right source edges |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/speed` | Visual playback rate (`0.25`–`4.0` whitelist). Audio is not re-timed |
| `POST` | `/api/v1/editor/projects/{id}/segments/{segmentId}/reset` | `playbackRate → 1.0`; IMAGE with a stored source slot → original VIDEO. Does not un-trim |
| `PUT` | `/api/v1/editor/projects/{id}/timeline` | Reorder by `segmentIds` (every id, no duplicates) |
| `POST` | `/api/v1/editor/projects/{id}/assets/images` | Upload JPEG/PNG/WEBP (signature-detected; Phase 1B) |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/visual` | Replace a segment's visual with an IMAGE asset (duration unchanged) |
| `POST` | `/api/v1/editor/projects/{id}/exports` | Start an export (`quality`: FAST / BALANCED / HIGH) |
| `GET` | `/api/v1/editor/exports/{exportId}/file` | Download export MP4 (`COMPLETED` only; streamed) |
| `POST` | `/api/v1/editor/exports/{exportId}/cancel` | Cancel an in-flight export (graceful FFmpeg stop, then force) |
| `GET` | `/api/v1/editor/projects/{id}/source` | Stream the original source MP4 (`Accept-Ranges` / HTTP 206) |
| `GET` | `/api/v1/editor/assets/{assetId}/content` | Stream any editor asset with HTTP Range |
| `GET` | `/api/v1/editor/exports/{exportId}/events` | SSE export progress / status events |
| `GET` | `/api/v1/editor/projects/{id}/events` | Same export events, keyed by project (latest export) |
| `DELETE` | `/api/v1/editor/projects/{id}` | Soft-delete project (blocked while an export is active) |

IMAGE uploads (`EDITOR_IMAGE_SEGMENTS_ENABLED=true` in Phase 1B) are classified by **file signature**, not by extension or `Content-Type`. Allowed: JPEG, PNG, WEBP (FFmpeg must be able to decode WEBP). Replacing a VIDEO slot with an image keeps that slot's duration and `sourceStartMillis`/`sourceEndMillis`, so the original audio timeline does not move.

### Audio Locked (V1)

Audio is **not** a timeline track. Export audio is always the original file from `0` to **`outputDurationMillis`**.

| Visual action | Audio |
|---|---|
| Reorder (`PUT .../timeline`, e.g. A B C D → C A D B) | **Not reordered.** Same original prefix, same pitch. |
| Speed (`PUT .../speed`) | **Not sped / not pitched.** Visual only (`setpts`). Never `atempo` / `asetrate`. |
| Whole-project trim (`PUT .../output-range`) | **Trimmed to the new output.** `original[0..outputDuration]` (e.g. 27.167s → 25.000s). |
| Split / merge-next / boundary | Metadata only until export. Audio file unchanged. |
| IMAGE replace | Slot duration stays; audio still `original[0..outputDuration]`. |

Slow-motion that would make output longer than the original audio is rejected (`OUTPUT_DURATION_EXCEEDS_AUDIO`). V1 does not loop audio or insert silence.

`durationMillis` / `sourceDurationMillis` = probed **source** length. `outputDurationMillis` = editor length (export `-t` and SSE 100%). Do not guess output from `durationMillis`.

### Video editor architecture

```text
Client (Swagger / UI)
        │
        ▼
VideoEditorController  /api/v1/editor
        │
        ├── VideoEditorService     upload / import / get / delete
        ├── EditorTimelineService  split / merge / boundary / output-range / trim / speed / reset / reorder / replace visual
        ├── EditorAssetService     IMAGE assets + Range source streaming
        ├── VideoEditorRenderService  export job + status transitions
        ├── FfmpegVisualReorderService  ProcessBuilder argv (no shell)
        ├── EditorEventHub         SSE (unthrottled UI ticks)
        └── PostgreSQL             video_project / video_asset / video_segment / video_export_job
```

- **Visual** may be split, merged, have its cut moved, trimmed, or played at a whitelist speed (`playbackRate`). Undo-split is `POST .../merge-next` (no undo stack): neighbors must be visually adjacent **and** source-contiguous (after `A1 | B | A2`, A1 cannot merge with B; reorder back to `A1 | A2 | B` and merge works). Shared-boundary drag (`PUT .../boundary`) uses the same neighbor rule: after reorder `C 20..30 | A 0..10`, do not treat the join as a source cut — backend flags `canResizeRightBoundary` / `canResizeLeftBoundary` / `canMergeNext` are authoritative. Canonical whole-project trim is `PUT .../output-range` (`{ startMillis: 0, endMillis: 25000 }` turns 27.167s into 25.000s); it rewrites segment ranges (no second stored window). Clip-edge `PUT .../trim` remains for first/last source handles; both write the same segment model. `POST .../reset` sets rate to 1.0 and can restore IMAGE → original VIDEO when the source slot is stored; it does **not** un-trim. IMAGE clips have a fixed duration slot — `PUT .../speed` returns `PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE`. See **Audio Locked** above. Segment `visualDurationMillis` is after speed; `sourceDurationMillis` is `sourceEnd - sourceStart`. Durations are computed (not DB columns).
- Export visual graphs are built only in `VisualReorderFilterGraph` (trim → reset PTS → speed → normalize → concat). Services pass argv; clients cannot supply FFmpeg filters.
- **Concurrency:** timeline mutations take `SELECT … FOR UPDATE` on the project so split and resize cannot corrupt positions. Optional `timelineVersion` (JSON body, or query on `merge-next` / `reset`) must match `project.timelineVersion` or the API returns `TIMELINE_CONFLICT` (409). JPA `@Version` on `video_project.timeline_version` also maps lost updates to that code. Omit `timelineVersion` to skip the stale-client check.
- V1 output is **MP4 + H.264** (`libx264`). Needs host **FFmpeg + FFprobe** with `libx264` and AAC (copy when the source codec is MP4-safe).
- Upload caps: source MP4 **`EDITOR_MAX_UPLOAD_BYTES` (512 MiB)**; IMAGE **`EDITOR_MAX_IMAGE_UPLOAD_BYTES` (20 MiB)**. Same cap is applied to Spring multipart and Tomcat form size.
- Export is async (`202`), statuses `CREATED → PREPARING → RENDERING → FINALIZING → COMPLETED|FAILED|CANCELLED`. Concurrent FFmpeg exports: `MAX_CONCURRENT_EDITOR_EXPORTS` (HTTP 429 when exceeded).
- Clients cannot pass filesystem paths or raw FFmpeg filters/commands.

V1 does not render a preview after each split. The browser previews from the original source using timeline metadata. `GET /api/v1/editor/projects/{id}/source` (and `GET /api/v1/editor/assets/{assetId}/content`) stream the file with **HTTP Range**: `Accept-Ranges: bytes`, `206 Partial Content`, `Content-Range`, and a matching `Content-Length`. The file is not loaded into heap. Export download is `GET /api/v1/editor/exports/{exportId}/file` (`COMPLETED` only), streamed the same way as recording download. Cancel is `POST /api/v1/editor/exports/{exportId}/cancel`: FFmpeg gets a graceful `q` first, then destroy / destroyForcibly after the stop timeout. Temp export files are deleted; recording processes are not touched.

Export `quality` is mapped in config (not client FFmpeg args):

| Quality | x264 preset | CRF | Why |
|---|---|---|---|
| `FAST` | `veryfast` | 26 | Faster drafts; CRF 26 sits in the 25–27 watchable band |
| `BALANCED` | `medium` | 23 | x264 defaults — normal delivery |
| `HIGH` | `slow` | 20 | More motion search; near-transparent for livestream footage |

V1 always keeps original audio (`keepOriginalAudio: true`). A 27.167s source trimmed to 25s exports audio `original[0..25s]` via `atrim=start=0:end=25` + `asetpts` (AAC encode). Full-length reorder (output ≈ source) still maps `-map 0:a:0?` and may `-c:a copy`. Visual speed uses `setpts=(PTS-STARTPTS)/rate` only — never `atempo`. Output length is explicit `-t` equal to **output** duration — not `-shortest`.

V1 container is MP4. If the source codec can be muxed into MP4 safely (`aac`, `mp3`, `ac3`/`eac3`, `alac`, `mp2`), FFmpeg uses `-c:a copy`. Otherwise the stream is encoded as AAC (`-c:a aac -b:a 192k`). Missing audio still produces a video-only MP4.

FPS values stay `ORIGINAL` / `24` / `25` / `30` / `50` / `60` (`FPS_24` aliases are accepted).

| FPS | Visual | Audio |
|---|---|---|
| `ORIGINAL` | Source frame rate | Locked `original[0..output]` (no `atempo` / `asetrate`) |
| `24` / `25` / `30` / `50` / `60` | Video normalized with `fps=` | Unchanged |

| Resolution | Landscape | Portrait |
|---|---|---|
| `ORIGINAL` | Source width×height (even) | Source width×height (even) |
| `1080p` | 1920×1080 | 1080×1920 |
| `720p` | 1280×720 | 720×1280 |
| `540p` | 960×540 | 540×960 |

Presets **never upscale**. A 1280×720 source exported as `1080p` stays 1280×720. Clips are letterboxed with `force_original_aspect_ratio=decrease` (no stretch).

Export runs FFmpeg through **ProcessBuilder argv** (no shell). The visual graph is `trim` + `setpts` (speed, `settb=AVTB` when needed) + letterbox (`scale=...:force_original_aspect_ratio=decrease`, center `pad`, `setsar=1`, `fps`, `format=yuv420p`) then `concat=v=1:a=0`. Locked audio is `atrim` to output duration when shorter than source, otherwise `-map 0:a:0?`. Portrait `ORIGINAL` (e.g. 1080×1920) stays vertical.

Recording → editor import uses a **hard link when the files are on the same volume**, so a multi-GB MP4 is not copied. Deleting the recording file afterwards does not break the editor project (the inode stays until the editor source is removed). If the OS/filesystem rejects the link (cross-device), the service falls back to a copy.

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

### Editor export SSE

`GET /api/v1/editor/exports/{exportId}/events` (`Content-Type: text/event-stream`)

Project-level `GET /api/v1/editor/projects/{id}/events` emits the same event names and payload for that project's latest export.

| Event | Meaning |
|---|---|
| `editor.export.started` | Export entered `CREATED` / `PREPARING` |
| `editor.export.progress` | FFmpeg progress tick (`RENDERING`) |
| `editor.export.finalizing` | Moving the rendered file into place |
| `editor.export.completed` | Output MP4 ready |
| `editor.export.failed` | Terminal failure |
| `editor.export.cancelled` | Cancel requested and applied |

Payload fields: `exportId`, `projectId`, `status`, `processedMillis`, `durationMillis`, `progressPercent`, `fps`, `speed` (numeric factor, e.g. `1.7` from FFmpeg `1.7x`). `durationMillis` is **output** length (`outputDurationMillis`), not source. A 27s source trimmed or sped to 25s reaches 100% at 25s. Progress is parsed from FFmpeg `-progress pipe:1` (`out_time_us`, then `out_time`, then `out_time_ms`; `N/A` ignored).

Errors use a consistent JSON body (`timestamp`, `status`, `code`, `message`, `path`). Sensitive URL query parameters are never returned to clients. Stack traces are never included.

### Editor error codes

| Code | HTTP | When |
|---|---|---|
| `EDITOR_PROJECT_NOT_FOUND` | 404 | Project missing or soft-deleted |
| `EDITOR_ASSET_NOT_FOUND` | 404 | Asset missing or not on the project |
| `EDITOR_SEGMENT_NOT_FOUND` | 404 | Segment id is not on the project timeline |
| `INVALID_EDITOR_FILE` | 400 | Empty, wrong type, or failed magic-byte check |
| `EDITOR_UPLOAD_TOO_LARGE` | 413 | Source/image upload exceeds the configured cap |
| `EDITOR_PROBE_FAILED` | 422 | ffprobe cannot read the source (no video / no duration) |
| `INVALID_SPLIT_POSITION` | 400 | `atMillis` is on a boundary or the clip is not VIDEO |
| `INVALID_SEGMENT_BOUNDARY` | 400 | `boundaryMillis` is outside the shared VIDEO cut, or neighbors are not source-contiguous (e.g. after reorder `C \| A`) |
| `SEGMENT_TOO_SHORT` | 400 | Clip would be shorter than `EDITOR_MIN_SEGMENT_DURATION_MS` (default 100 ms) |
| `INVALID_SEGMENT_TRIM` | 400 | Trim/output-range would expand, move a shared cut, or target a middle clip edge |
| `SEGMENTS_NOT_MERGEABLE` | 409 | Neighbors are not the same VIDEO source cut (reorder, IMAGE, or last clip) |
| `INVALID_PLAYBACK_RATE` | 400 | `playbackRate` is not on the V1 whitelist |
| `PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE` | 400 | `PUT .../speed` on an IMAGE clip |
| `INVALID_OUTPUT_DURATION` | 400 | Output duration (sum of visual clips) is not greater than 0 |
| `OUTPUT_DURATION_EXCEEDS_AUDIO` | 400 | Slow-motion (or other lengthening) would exceed original audio |
| `INVALID_TIMELINE` | 400 | Reorder/coverage/segment set is invalid |
| `TIMELINE_CONFLICT` | 409 | `timelineVersion` does not match (stale client) or JPA optimistic lock failed |
| `INVALID_EDITOR_STATE` | 409 | Project status does not allow the operation |
| `INVALID_EDITOR_EXPORT` | 400 | Export settings are invalid |
| `EXPORT_ALREADY_RUNNING` | 409 | Mutate or start export while an export is active |
| `EXPORT_NOT_FOUND` | 404 | Export id does not exist |
| `EXPORT_NOT_READY` | 409 | Download/cancel when the export is not in the required state |
| `EXPORT_FAILED` | 500 | Render failed (generic client message only) |
| `EDITOR_STORAGE_ERROR` | 500 | Editor disk/path operation failed (generic client message only) |
| `CONCURRENT_EDITOR_LIMIT_EXCEEDED` | 429 | Too many simultaneous editor exports (`MAX_CONCURRENT_EDITOR_EXPORTS`) |
| `MEDIA_EXECUTABLE_MISSING` | 503 | ffmpeg/ffprobe not available |

`EXPORT_FAILED` and `EDITOR_STORAGE_ERROR` always use the default message. FFmpeg argv, stderr, and absolute filesystem paths are logged server-side only.

### Editor acceptance (curl)

Dev base: `http://localhost:8081`. Replace `$ID` / `$A` / `$B` / `$C` / `$D` / `$EXPORT` from JSON responses. Optional `timelineVersion` may be sent on mutation bodies.

**Case 1 — Undo split** (`A` → `A1|A2` → `A`):

```bash
curl -s -X POST "http://localhost:8081/api/v1/editor/projects/$ID/segments/$A/split" \
  -H "Content-Type: application/json" -d "{\"atMillis\":5000}"
curl -s -X POST "http://localhost:8081/api/v1/editor/projects/$ID/segments/$A/merge-next"
```

**Case 2 — Resize cut** (`A 0..5 | B 5..10` → boundary 6s):

```bash
curl -s -X PUT "http://localhost:8081/api/v1/editor/projects/$ID/segments/$A/boundary" \
  -H "Content-Type: application/json" -d "{\"boundaryMillis\":6000}"
```

**Case 3 — Trim whole video** (27.167s → 25.000s, then export; audio ≈ 25s):

```bash
curl -s -X PUT "http://localhost:8081/api/v1/editor/projects/$ID/output-range" \
  -H "Content-Type: application/json" -d "{\"startMillis\":0,\"endMillis\":25000}"
curl -s -X POST "http://localhost:8081/api/v1/editor/projects/$ID/exports" \
  -H "Content-Type: application/json" -d "{\"quality\":\"FAST\"}"
# SSE 100% uses outputDurationMillis (25s), not source 27s:
# GET /api/v1/editor/exports/$EXPORT/events
curl -s -o export.mp4 "http://localhost:8081/api/v1/editor/exports/$EXPORT/file"
ffprobe -v error -show_entries format=duration -of default=nk=1:nw=1 export.mp4
```

**Case 4 — Visual speed** (10s clip → 2x → visual ≈ 5s; audio not 2x):

```bash
curl -s -X PUT "http://localhost:8081/api/v1/editor/projects/$ID/segments/$A/speed" \
  -H "Content-Type: application/json" -d "{\"playbackRate\":2.0}"
```

**Case 5 — Reorder** (A B C D → C A D B; audio stays original prefix):

```bash
curl -s -X PUT "http://localhost:8081/api/v1/editor/projects/$ID/timeline" \
  -H "Content-Type: application/json" \
  -d "{\"segmentIds\":[\"$C\",\"$A\",\"$D\",\"$B\"]}"
```

**Case 6 — IMAGE replace** (requires `EDITOR_IMAGE_SEGMENTS_ENABLED=true`):

```bash
curl -s -X POST "http://localhost:8081/api/v1/editor/projects/$ID/assets/images" -F "file=@still.png"
curl -s -X PUT "http://localhost:8081/api/v1/editor/projects/$ID/segments/$A/visual" \
  -H "Content-Type: application/json" -d "{\"assetId\":\"$ASSET\"}"
```

**Case 7 — tests:** `.\mvnw.cmd test` (PowerShell: quote `-Dtest=...` because commas are parsed).

**Case 8 — livestream APIs unchanged:** `POST /api/v1/streams/probe`, `POST /api/v1/recordings`, SSE `/recordings/{id}/events`.

Create a project first:

```bash
curl -s -X POST "http://localhost:8081/api/v1/editor/projects" -F "file=@clip.mp4" -F "name=Cut 1"
```

### Editor security

- Paths are confined to `EDITOR_STORAGE_DIRECTORY` (path traversal is rejected).
- Stored files use generated UUID names; client filenames are display metadata only.
- Uploads are size-capped and content-type whitelisted; MP4/JPEG/PNG/WEBP are confirmed by magic bytes.
- Clients cannot pass filesystem paths or raw FFmpeg filters/commands.
- Assets must belong to the project; timeline mutations run in a transaction.
- Failed create (copy/probe/persist) deletes written files and hides the project.
- Create metadata, split, reorder, export-job insert, and export status transitions are transactional. File deletes run after the DB commit; create failures compensate by removing editor files.
- Editor delete refuses an active export, then soft-deletes the project and removes only editor-owned files (`source/`, `assets/`, `exports/`, `tmp/`). Recording originals are never deleted (hardlink = unlink the editor entry only; copy = delete the editor copy).
- Concurrent FFmpeg exports are capped separately from recordings; overflow is HTTP 429 (`CONCURRENT_EDITOR_LIMIT_EXCEEDED`), not an unbounded queue.
- API responses never include absolute filesystem paths or stack traces.

### Editor logging

INFO logs include `projectId`, `exportId`, status transitions (`from`/`to`), throttled progress summaries (`processedMs`, `durationMs`, `percent`, `fps`, `speed`), source metadata (duration, WxH, fps, codecs), and export settings (fps/resolution/codec/quality). Progress summaries use `PROGRESS_PERSIST_INTERVAL_SECONDS` (default 3s) — **not** every FFmpeg frame.

Not logged at INFO: original filenames/paths, multipart bytes, stream URL query tokens, FFmpeg argv/`-filter_complex`, or FFmpeg stderr. Failures log a generic server error; clients get `EXPORT_FAILED` without those details.

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
