# VH MEDIA LIVE DOWNLOADER — Tài liệu các phần đã làm

Tài liệu mô tả phạm vi backend đã triển khai trong repository này.  
Chi tiết chạy local xem [README.md](../README.md); triển khai production xem [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md).

---

## 1. Mục tiêu hệ thống

Spring Boot service ghi livestream từ **URL stream trực tiếp** (HTTP/HTTPS: FLV, HLS, …), lưu MPEG-TS tạm, remux sang MP4, theo dõi tiến độ qua SSE.

| Đã làm | Chưa làm (ngoài scope) |
|---|---|
| Probe + record URL người dùng cung cấp | Tự lấy / refresh URL TikTok (hay platform khác) |
| API REST + Swagger | UI Angular (repo riêng / chưa có trong backend này) |
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
Controllers (probe, recordings, health)
        │
        ├── StreamProbeService ──► FfprobeService
        ├── RecordingJobService ──► create / list / get / delete
        ├── RecordingLifecycleService ──► stop / remux / complete / fail
        ├── FfmpegRecordingService ──► ghi .ts
        ├── FfmpegRemuxService ──► .ts → .mp4
        ├── RecordingEventHub ──► SSE
        └── LiveDownloadJob (PostgreSQL)
```

Package chính: `com.vhmedia.livedownloader`

| Nhóm | Vai trò |
|---|---|
| `controller` | REST endpoints |
| `service` | Nghiệp vụ job, probe, lifecycle, recovery, progress |
| `media` | Bọc FFmpeg / FFprobe, HTTP headers, SSE hub |
| `entity` / `repository` | Job persistence |
| `dto` | Request / response |
| `config` | Media, CORS, security headers, OpenAPI, async, disk health |
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
- Group endpoints: probe, recordings, health

---

## 5. Cấu trúc thư mục liên quan

```text
live-downloader/
├── README.md                 # Hướng dẫn chạy local + API overview
├── docs/DEPLOYMENT.md        # Deploy production
├── doc/CAC_PHAN_DA_LAM.md    # Tài liệu này
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

---

## 7. Kiểm thử đã có

- Unit / MockMvc: controller, service, exception handler, URL redactor, security headers, media classifier, …
- Integration: Flyway migration (Testcontainers Postgres), FFmpeg synthetic media IT
- Profile test: `application-test.yml`

---

## 8. Ghi chú vận hành quan trọng

- Backend **không** tự lấy stream URL từ TikTok/platform — user phải cung cấp URL còn hiệu lực.
- Token CDN hết hạn → probe/record fail; cần URL mới.
- Restart app giữa lúc đang ghi → job `INTERRUPTED`, không resume tự động.
- Chỉ dùng với URL bạn được phép truy cập / ghi lại.

---

*Tài liệu phản ánh trạng thái codebase tại thời điểm viết. Khi bổ sung tính năng mới, cập nhật file này cùng README / DEPLOYMENT nếu cần.*
