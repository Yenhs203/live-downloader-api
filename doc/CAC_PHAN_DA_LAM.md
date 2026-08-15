# VH MEDIA LIVE DOWNLOADER — Tài liệu các phần đã làm

Tài liệu mô tả phạm vi backend đã triển khai trong repository này.  
Chi tiết chạy local xem [README.md](../README.md); triển khai production xem [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md).  
Nhật ký ngày 14/08/2026 (prompt Video Editor mục 1–36): [2026-08-14-VIDEO-EDITOR-V1.md](./2026-08-14-VIDEO-EDITOR-V1.md).

---

## 1. Mục tiêu hệ thống

Spring Boot service ghi livestream từ **URL stream trực tiếp** (HTTP/HTTPS: FLV, HLS, …), lưu MPEG-TS tạm, remux sang MP4, theo dõi tiến độ qua SSE.

| Đã làm | Chưa làm (ngoài scope) |
|---|---|
| Probe + record URL người dùng cung cấp | Tự lấy / refresh URL TikTok (hay platform khác) |
| API REST + Swagger (group Video Editor) | UI Angular (repo riêng / chưa có trong backend này) |
| Editor V1: upload, split, reorder visual, trim, speed, export H.264, audio locked | Delete-with-padding, duplicate-with-trim, transition, overlay, BGM |
| PostgreSQL + Flyway | Dockerize FFmpeg |

---

## 2. Công nghệ đã dùng

| Thành phần | Lựa chọn |
|---|---|
| Runtime | Java 21, Spring Boot |
| API docs | springdoc-openapi (Swagger UI) |
| DB | PostgreSQL 16+, Flyway migration |
| Media | FFmpeg (ghi + remux), FFprobe (probe) |
| Local DB | Docker Compose (`docker-compose.yml`) |
| Test | JUnit, MockMvc, Testcontainers (Postgres), IT với FFmpeg tổng hợp |

---

## 3. Kiến trúc tổng quan

```text
Client (Swagger / UI)
        │
        ▼
Controllers (probe, recordings, editor, health)
        │
        ├── StreamProbeService ──► FfprobeService
        ├── RecordingJobService ──► create / list / get / delete
        ├── RecordingLifecycleService ──► stop / remux / complete / fail
        ├── FfmpegRecordingService ──► ghi .ts
        ├── FfmpegRemuxService ──► .ts → .mp4
        ├── RecordingEventHub ──► SSE recording
        ├── VideoEditorService ──► upload / import / get / delete
        ├── EditorTimelineService ──► split / merge / boundary / reorder
        ├── VideoEditorRenderService ──► export job + status
        ├── FfmpegVisualReorderService ──► trim/setpts/concat visual, map original audio
        ├── EditorEventHub ──► SSE export
        └── PostgreSQL (live_download_job, video_project, video_asset, video_segment, video_export_job)
```

Package chính: `com.vhmedia.livedownloader`

| Nhóm | Vai trò |
|---|---|
| `controller` | REST endpoints |
| `service` | Nghiệp vụ recording + editor (`VideoEditorService` project, `EditorTimelineService` split/reorder, `EditorAssetService`, `VideoEditorRenderService` export) |
| `media` | Bọc FFmpeg / FFprobe, HTTP headers, SSE hub |
| `entity` / `repository` | Job + editor persistence |
| `dto` | Request / response |
| `editor` | Timeline validator, export planner, filter graph, codec policy |
| `config` | Media, editor, CORS, security headers, OpenAPI groups, async, disk health |
| `exception` | Error codes + `GlobalExceptionHandler` |
| `util` | Path resolver, URL redactor, process cleanup |

---

## 4. Các phần chức năng đã hoàn thành

### 4.1. Probe stream

- Endpoint: `POST /api/v1/streams/probe`
- Body: `{ "url": "..." }`
- Validate URL (chỉ `http` / `https`)
- Gọi FFprobe lấy format, codec video/audio, resolution, fps
- Reject nếu không có video
- Timeout cấu hình qua `PROBE_TIMEOUT_SECONDS`
- Có thể gửi browser User-Agent + Referer (hỗ trợ CDN kiểu TikTok)

### 4.2. Tạo và ghi recording

- Endpoint: `POST /api/v1/recordings`
- Body: `{ "streamUrl": "..." }` → `202 Accepted`
- Flow: tạo job → probe → start FFmpeg (stream copy → MPEG-TS)
- Giới hạn số job song song (`MAX_CONCURRENT_RECORDINGS`)
- Kiểm tra dung lượng đĩa trước khi start (`MIN_FREE_DISK_BYTES`)

### 4.3. Quản lý job

| Method | Path | Mô tả |
|---|---|---|
| `GET` | `/api/v1/recordings` | List (page, size, filter `status` / `activeOnly`) |
| `GET` | `/api/v1/recordings/{id}` | Chi tiết job |
| `POST` | `/api/v1/recordings/{id}/stop` | Dừng graceful |
| `DELETE` | `/api/v1/recordings/{id}` | Soft-delete + xóa file (khi trạng thái cho phép) |
| `GET` | `/api/v1/recordings/{id}/file` | Tải MP4 khi `COMPLETED` |
| `GET` | `/api/v1/health` | Liveness |

### 4.4. SSE tiến độ realtime

- Endpoint: `GET /api/v1/recordings/{id}/events`
- Subscribe nhận snapshot trạng thái hiện tại ngay lập tức
- Sự kiện: `recording.started`, `recording.progress`, `recording.stopping`, `recording.remuxing`, `recording.completed`, `recording.failed`
- Progress gồm duration, bytes, fps, speed, bitrate (persist DB theo interval)

### 4.5. Remux TS → MP4

- Sau khi FFmpeg ghi dừng (user stop hoặc stream kết thúc)
- Remux stream-copy (không re-encode)
- Map audio tùy chọn (`-map 0:a:0?`) — thiếu audio vẫn hoàn tất nếu có video
- Có thể xóa file `.ts` tạm sau remux (`DELETE_TEMP_AFTER_REMUX`)

### 4.6. Vòng đời trạng thái job

```text
CREATED → PROBING → READY → RECORDING → STOPPING → REMUXING → COMPLETED
                                              ↘ FAILED
App restart khi đang ghi → INTERRUPTED
Soft-delete → DELETED
```

| Status | Ý nghĩa |
|---|---|
| `CREATED` | Đã tạo bản ghi |
| `PROBING` | Đang ffprobe |
| `READY` | Probe OK, chuẩn bị FFmpeg |
| `RECORDING` | Đang ghi `.ts` |
| `STOPPING` | Đã yêu cầu dừng |
| `REMUXING` | Đang remux MP4 |
| `COMPLETED` | Có file MP4 cuối |
| `FAILED` | Lỗi probe / ghi / remux |
| `INTERRUPTED` | Mất process (ví dụ restart app) |
| `DELETED` | Soft-delete, ẩn khỏi list mặc định |

### 4.7. Startup recovery

- Job `RECORDING` / `STOPPING` khi app restart → `INTERRUPTED`
- Job `REMUXING`: nếu đã có MP4 hợp lệ → `COMPLETED`, ngược lại → `INTERRUPTED`
- **Không** tự restart ghi lại URL cũ (token CDN có thể hết hạn)

### 4.8. Lưu trữ file

- Thư mục theo profile: `./recordings-dev` (dev) hoặc `RECORDINGS_DIRECTORY`
- Basename do server sinh (không lấy từ input user), ví dụ `live_20260811_143000_a1b2c3d4`
- File: `.ts` (tạm) → `.mp4` (final)
- Path bị khóa trong root recordings (chống path traversal)

### 4.9. Persistence (PostgreSQL)

Bảng `live_download_job` (Flyway `V1__create_live_download_job.sql`):

- Metadata: URL gốc, status, codec, width/height/fps
- Progress: `downloaded_bytes`, `duration_millis`
- Đường dẫn temp / final, timestamps, `error_message`
- Index theo `status`, `created_at`

### 4.10. Xử lý lỗi thống nhất

- `GlobalExceptionHandler` + enum `ErrorCode`
- Response JSON: `timestamp`, `status`, `code`, `message`, `path`
- URL query nhạy cảm được redact trong log / message client (`UrlRedactor`)

Một số mã lỗi chính:

| Code | HTTP | Khi nào |
|---|---|---|
| `INVALID_STREAM_URL` | 400 | URL không hợp lệ |
| `STREAM_PROBE_FAILED` | 422 | Không đọc được stream / không có video |
| `STREAM_PROBE_TIMEOUT` | 504 | Probe quá thời gian |
| `CONCURRENT_LIMIT_EXCEEDED` | 429 | Vượt giới hạn song song |
| `INVALID_RECORDING_STATE` | 409 | Thao tác sai trạng thái |
| `MEDIA_EXECUTABLE_MISSING` | 503 | Thiếu ffmpeg/ffprobe |

### 4.11. Bảo mật & cấu hình vận hành đã có

- CORS theo origin (`APP_CORS_ALLOWED_ORIGINS`, prod bắt buộc, không dùng `*`)
- Security response headers (`SecurityHeadersFilter`)
- Tùy chọn chặn địa chỉ private khi probe/record (SSRF, `BLOCK_PRIVATE_STREAM_ADDRESSES`)
- Browser headers cho FFmpeg/FFprobe (`HTTP_USER_AGENT`, `HTTP_REFERER`)
- Actuator disk space health
- Validate ffmpeg/ffprobe lúc boot (prod)
- Graceful shutdown coordinator cho recording đang chạy
- Profile `dev` / `prod` tách cấu hình

### 4.12. OpenAPI / Swagger

- Dev: `http://localhost:8081/swagger-ui/index.html`
- Groups / tags: **Video Editor**, **Recordings**, **Stream Probe**, **Health**
- Video Editor documents: upload, project, split, merge-next, boundary, output-range, trim, speed, timeline, export, events, download
- Prod: `springdoc` tắt (`application-prod.yml`)

### 4.13. Video editor (visual reorder)

Domain riêng (`VideoProject` / `VideoAsset` / `VideoSegment` / `VideoExportJob`) — **không** tái sử dụng `LiveDownloadJob`.

**Audio Locked (V1):** audio không phải timeline track. Export audio luôn `original[0 .. outputDurationMillis]`.

| Hành động visual | Audio |
|---|---|
| Reorder (`PUT .../timeline`, ví dụ A B C D → C A D B) | **Không** reorder. Cùng prefix gốc, cùng pitch. |
| Speed (`PUT .../speed`) | **Không** speed / pitch. Chỉ `setpts` trên visual. Không `atempo` / `asetrate`. |
| Trim cả video (`PUT .../output-range`) | **Có** trim theo output. Ví dụ nguồn 27.167s → 25.000s thì audio ≈ 25s (`atrim`+AAC). |

Output ≈ nguồn → `-map 0:a:0?` (copy nếu codec MP4-safe). Không `-shortest`. Slow-motion làm output dài hơn audio gốc → `OUTPUT_DURATION_EXCEEDS_AUDIO` (V1 không loop / không silence). `durationMillis` = duration nguồn (compat); `sourceDurationMillis` = nguồn; `outputDurationMillis` = tổng visual (sau speed) — FE không đoán output từ `durationMillis`. Segment có `sourceDurationMillis` + `visualDurationMillis` + `canMergeNext` / `canResizeRightBoundary` / `canResizeLeftBoundary` (backend authoritative: chỉ true khi hai visual neighbor VIDEO cùng asset, cùng rate, source-contiguous). Sau reorder `C 20..30 | A 0..10` không kéo shared boundary — `PUT .../boundary` → `INVALID_SEGMENT_BOUNDARY`. Canonical project trim: `PUT .../output-range` (`startMillis`/`endMillis` trên visual output, ví dụ 27.167s → 25.000s); rewrite segment, không lưu output window thứ hai. `PUT .../trim` chỉ mép source clip đầu/cuối. Container MP4 + H.264.

**Concurrency:** `SELECT … FOR UPDATE` trên `video_project` khi mutate timeline (split + resize không race positions). Optional `timelineVersion` (body JSON; query trên `merge-next`/`reset`) phải khớp `project.timelineVersion` — lệch → `TIMELINE_CONFLICT` (409). JPA `@Version` cột `timeline_version` (Flyway V11). Bỏ field thì skip check (compat).

| Method | Path | Mô tả |
|---|---|---|
| `GET` | `/api/v1/editor/options` | Preset export (fps/resolution/codec/quality). `keepOriginalAudio` luôn true |
| `POST` | `/api/v1/editor/projects` | Upload MP4 (`multipart` `file`, optional `name`) |
| `POST` | `/api/v1/editor/projects/from-recording/{recordingId}` | Import recording `COMPLETED` (hardlink, fallback copy) |
| `GET` | `/api/v1/editor/projects` | List |
| `GET` | `/api/v1/editor/projects/{id}` | Chi tiết project + timeline + export |
| `POST` | `/api/v1/editor/projects/{id}/segments/{segmentId}/split` | Split VIDEO tại `atMillis` |
| `POST` | `/api/v1/editor/projects/{id}/segments/{segmentId}/merge-next` | Undo split: nối với clip kế tiếp (cùng source cut, cùng rate) |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/boundary` | Kéo điểm cắt chung (`boundaryMillis`) — chỉ khi `canResizeRightBoundary` |
| `PUT` | `/api/v1/editor/projects/{id}/output-range` | Trim cả project trên visual output (`startMillis`/`endMillis`; 27.167s → 25.000s). Audio trim theo output |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/trim` | Trim mép đầu clip đầu / mép cuối clip cuối |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/speed` | Visual playback rate (whitelist 0.25–4.0). **Không** speed audio |
| `POST` | `/api/v1/editor/projects/{id}/segments/{segmentId}/reset` | `playbackRate → 1.0`; IMAGE có source slot → VIDEO gốc. Không un-trim (không lưu original bounds) |
| `PUT` | `/api/v1/editor/projects/{id}/timeline` | Reorder `segmentIds` (đủ mọi id, không trùng). **Không** reorder audio |
| `POST` | `/api/v1/editor/projects/{id}/assets/images` | Upload JPEG/PNG/WEBP (Phase 1B, `EDITOR_IMAGE_SEGMENTS_ENABLED`) |
| `PUT` | `/api/v1/editor/projects/{id}/segments/{segmentId}/visual` | Thay visual bằng IMAGE (duration slot không đổi) |
| `POST` | `/api/v1/editor/projects/{id}/exports` | Start export (`202`) |
| `GET` | `/api/v1/editor/exports/{exportId}/events` | SSE tiến độ export. `durationMillis` / 100% = `outputDurationMillis` (không dùng source; 27s trim còn 25s → 100% tại 25s) |
| `GET` | `/api/v1/editor/projects/{id}/events` | SSE theo project (export mới nhất) |
| `GET` | `/api/v1/editor/exports/{exportId}/file` | Download MP4 khi `COMPLETED` |
| `POST` | `/api/v1/editor/exports/{exportId}/cancel` | Hủy export (FFmpeg `q` rồi destroy) |
| `DELETE` | `/api/v1/editor/projects/{id}` | Soft-delete (chặn khi export đang chạy) |

Path param mặc định `{id}`; endpoint theo export dùng `{exportId}`.

**Export status:** `CREATED` → `PREPARING` → `RENDERING` → `FINALIZING` → `COMPLETED` \| `FAILED` \| `CANCELLED`. Mutate timeline khi export active → `EXPORT_ALREADY_RUNNING` (409).

**FFmpeg:** ProcessBuilder argv (không shell). Filter visual được build trong `VisualReorderFilterGraph` (không ghép string ở controller/service): VIDEO `trim` → reset PTS → `setpts` speed → normalize canvas/FPS khi có plan; IMAGE chỉ loop `-t` duration slot (không `playbackRate`); `concat=v=1:a=0`. Audio locked: `atrim=start=0:end=<outputDuration>` + `asetpts` + AAC khi output ngắn hơn nguồn; output ≈ nguồn thì `-map 0:a:0?` (copy nếu MP4-safe). Không `-c:a copy` khi đã dùng audio filter. Timeout `EDITOR_EXPORT_TIMEOUT_MINUTES`. Concurrent cap `MAX_CONCURRENT_EDITOR_EXPORTS` (HTTP 429).

**Upload limit:** nguồn MP4 `EDITOR_MAX_UPLOAD_BYTES` (mặc định 512 MiB) — Spring multipart + Tomcat form size. IMAGE `EDITOR_MAX_IMAGE_UPLOAD_BYTES` (20 MiB). Magic-byte MP4/JPEG/PNG/WEBP.

**Storage:** `EDITOR_STORAGE_DIRECTORY` (mặc định `{recordings}/editor`). File tên UUID; path traversal bị từ chối. Delete chỉ xóa file editor; recording original không bị xóa (hardlink = unlink entry editor).

### 4.14. Logging editor

INFO: `projectId`, `exportId`, status transition (`from`/`to`), progress summary (throttled `PROGRESS_PERSIST_INTERVAL_SECONDS`), duration, input metadata (WxH/fps/codec), export settings.

Không log INFO: filename/path gốc, multipart bytes, token URL, FFmpeg argv / `-filter_complex`, stderr từng frame. SSE UI vẫn nhận progress mỗi tick; log server không spam frame.

### 4.15. Acceptance editor (demo)

| Case | Kỳ vọng | API |
|---|---|---|
| 1 Undo split | `A` → split → `A1\|A2` → merge-next → `A` | `POST .../split` rồi `POST .../merge-next` |
| 2 Resize cut | `A 0..5 \| B 5..10` → boundary 6s → `A 0..6 \| B 6..10` | `PUT .../boundary` `{ "boundaryMillis": 6000 }` |
| 3 Trim whole video | nguồn 27.167s → output-range 25s → export video **và** audio ≈ 25s | `PUT .../output-range` rồi `POST .../exports` |
| 4 Visual speed | clip 10s → 2x → visual ≈ 5s; **audio không 2x** | `PUT .../speed` `{ "playbackRate": 2.0 }` |
| 5 Reorder | `A B C D` → `C A D B` vẫn chạy; audio prefix gốc | `PUT .../timeline` |
| 6 IMAGE replace | thay visual IMAGE (nếu `EDITOR_IMAGE_SEGMENTS_ENABLED`) | `POST .../assets/images` + `PUT .../visual` |
| 7 Tests | `.\mvnw.cmd test` pass | |
| 8 Livestream | probe / recordings / SSE recording không regression | `/api/v1/streams/probe`, `/api/v1/recordings` |

Curl chi tiết: [README.md — Editor acceptance](../README.md#editor-acceptance-curl).

---

## 5. Cấu trúc thư mục liên quan

```text
live-downloader/
├── README.md                 # Hướng dẫn chạy local + API overview
├── docs/DEPLOYMENT.md        # Deploy production
├── doc/CAC_PHAN_DA_LAM.md              # Tài liệu này
├── doc/2026-08-14-VIDEO-EDITOR-V1.md   # Nhật ký prompt editor 14/08/2026
├── docker-compose.yml        # PostgreSQL local
├── .env.example
└── src/main/java/.../livedownloader/
    ├── controller/
    ├── service/
    ├── media/
    ├── config/
    ├── entity/ + repository/
    ├── dto/
    ├── exception/
    └── util/
```

---

## 6. Cách dùng nhanh (sau khi app đã chạy)

1. Lấy URL stream trực tiếp (ví dụ từ DevTools Network → request `.flv` / playlist).
2. (Tuỳ chọn) Probe: `POST /api/v1/streams/probe` với `{ "url": "..." }`.
3. Bắt đầu ghi: `POST /api/v1/recordings` với `{ "streamUrl": "..." }`.
4. Theo dõi: `GET /api/v1/recordings/{id}/events` hoặc poll `GET /api/v1/recordings/{id}`.
5. Dừng: `POST /api/v1/recordings/{id}/stop`.
6. Tải MP4: `GET /api/v1/recordings/{id}/file` khi status = `COMPLETED`.
7. Editor: `POST /api/v1/editor/projects` (upload) hoặc `/from-recording/{id}` → split / merge-next / boundary / output-range / speed / reorder → `POST .../exports` → SSE `/exports/{exportId}/events` (100% theo `outputDurationMillis`) → download `/exports/{exportId}/file`.

Hoặc mở Swagger group **Video Editor**: `http://localhost:8081/swagger-ui/index.html`.

---

## 7. Kiểm thử đã có

- Unit / MockMvc: controller, service, exception handler, URL redactor, security headers, media classifier, editor validators (duration/split/reorder), export planner, filter graph, path resolver, export status, error mapping
- Controller MockMvc editor: upload, get project, split, merge-next, boundary, output-range, speed, reorder, IMAGE replace, export, invalid request, download not ready
- Integration: Flyway (Testcontainers Postgres), FFmpeg synthetic remux IT, FFmpeg visual-reorder IT (CADB reorder; 27.167s → 25s trim video+audio; skip nếu thiếu ffmpeg/ffprobe)
- Profile test: `application-test.yml`

---

## 8. Ghi chú vận hành quan trọng

- Backend **không** tự lấy stream URL từ TikTok/platform — user phải cung cấp URL còn hiệu lực.
- Token CDN hết hạn → probe/record fail; cần URL mới.
- Restart app giữa lúc đang ghi → job `INTERRUPTED`, không resume tự động.
- Restart app giữa lúc đang export editor → job export `FAILED` (process FFmpeg không sống sót JVM).
- Chỉ dùng với URL bạn được phép truy cập / ghi lại.

---

*Tài liệu phản ánh trạng thái codebase tại thời điểm viết. Khi bổ sung tính năng mới, cập nhật file này cùng README / DEPLOYMENT nếu cần.*
