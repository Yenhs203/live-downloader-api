# Nhật ký 14/08/2026 — Module Video Editor V1

Tài liệu ghi lại **toàn bộ prompt** gửi trong ngày 14/08/2026 và **kết quả đã triển khai** trên repository backend VH MEDIA LIVE DOWNLOADER.

Tài liệu tổng hợp lâu dài: [CAC_PHAN_DA_LAM.md](./CAC_PHAN_DA_LAM.md).  
Chạy local: [README.md](../README.md). Deploy: [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md).

---

## Cập nhật 15/08/2026 — V1 hoàn tất (mục 19–36)

Phần **2 / 4–5** bên dưới là nhật ký prompt gốc ngày 14/08 (audio “không trim”). **Hành vi hiện tại** khác ở điểm trim:

**Audio Locked**

- Reorder visual **không** reorder audio.
- Speed visual **không** speed / pitch audio (không `atempo` / `asetrate`).
- Trim output (`PUT .../output-range`) **có** trim audio theo output: `original[0 .. outputDurationMillis]`.

**Timeline invariants**

- Segment là source of truth (không cột output-window thứ hai, không duration derived trên DB).
- `durationMillis` / `sourceDurationMillis` = nguồn. `outputDurationMillis` = tổng visual sau speed = export `-t` và SSE 100%.
- Merge-next / boundary: visual neighbor VIDEO, cùng asset, cùng rate, `left.sourceEnd ≈ right.sourceStart`. Flag backend (`canMergeNext`, `canResizeRightBoundary`, `canResizeLeftBoundary`) là authoritative.
- Undo-split = `POST .../merge-next` (không undo stack).
- Flyway tới **V11** (`timeline_version`). Không thêm V12 cho docs này. Không env mới cho Audio Locked.

Acceptance curl: [README.md](../README.md#editor-acceptance-curl).

---

## 0. Thông tin phiên làm việc

| Mục | Giá trị |
|---|---|
| Ngày | Thứ Sáu, 14/08/2026 |
| Repo | `live-downloader` (package `com.vhmedia.livedownloader`) |
| Stack | Java 21, Spring Boot 4.0.7, Maven, PostgreSQL 16, Flyway, FFmpeg/FFprobe |
| Base API | `/api/v1` |
| Editor base | `/api/v1/editor` |
| Port dev | `8081` |
| Constraint cứng | Không đổi behavior livestream recording; không reuse `LiveDownloadJob` |

Prompt được gửi lần lượt từ mục **1 → 36** (khoảng 10:16–14:48). Cuối ngày tách `EditorTimelineService` theo mục 34.

`.\mvnw.cmd test` — **pass** (exit 0).

---

## 1. Bối cảnh source hiện tại

Backend đã có: probe livestream, recording, ghi MPEG-TS, remux TS → MP4, download, wrapper FFmpeg/FFprobe, background job, SSE, error handling, PostgreSQL, path security.

**Đã tuân thủ:** đọc structure trước khi code; reuse convention hiện có; không refactor lớn recording; API `/api/v1/recordings` và `/api/v1/streams/probe` giữ nguyên.

---

## 2. Mục tiêu module mới

Người dùng upload MP4 (hoặc lấy recording `COMPLETED`) → split visual → reorder (ví dụ A-B-C-D thành C-A-D-B).

**Rule quan trọng nhất (prompt gốc 14/08):** chỉ visual đổi thứ tự. Audio gốc luôn `source[0..duration]`, không reorder / trim / re-time.

> **Hành vi shipped (15/08):** Audio Locked = `original[0..outputDuration]`. Reorder/speed vẫn không đụng audio; **trim output thì trim audio**. Xem mục “Cập nhật 15/08/2026” ở đầu file.

```text
Visual:  C | A | D | B
Audio:   00:00 ---------- original audio ---------- END
```

---

## 3. Phạm vi Version 1

**Đã làm**

- Upload MP4 + tạo project từ recording `COMPLETED`
- FFprobe: duration, width, height, FPS, video/audio codec, `hasAudio`
- Split / reorder (segment = metadata, không cắt file vật lý lúc edit)
- Preview qua source + HTTP Range (không render preview mỗi lần split)
- Audio locked
- Export H.264, FPS `ORIGINAL/24/25/30/50/60`, resolution `ORIGINAL/1080p/720p/540p`
- SSE progress, download, cancel
- Error handling + tests

**Chưa làm (đúng scope V1):** transition, text overlay, subtitle, filter màu, speed ramp, BGM, multi-track audio, crop UI, …

---

## 4–5. Nguyên tắc timeline + rule V1

Split ví dụ: A `0–10s`, B `10–25s`, C `25–40s`, D `40–60s`.  
Reorder C-A-D-B → visual `source[25..40] + [0..10] + [40..60] + [10..25]`.  
Audio vẫn `source[0..60]` khi output ≈ nguồn. Khi `output-range` cắt còn 25s, audio = `original[0..25s]`.

V1 hiện có **split + merge-next + boundary + output-range + speed + reorder** (và replace visual bằng IMAGE, duration slot không đổi). Không xóa/duplicate segment làm lệch coverage nguồn trừ khi crop output. Timeline dùng **integer milliseconds**. Epsilon `EDITOR_COVERAGE_EPSILON_MILLIS` (mặc định 50 ms).

Validator: `EditorSegmentValidator`.

---

## 6. Image segment

Model có `EditorSegmentType.VIDEO` và `IMAGE` từ đầu.

| Phase | Trạng thái |
|---|---|
| 1A | Split/reorder VIDEO cùng source — **xong** |
| 1B | Upload JPEG/PNG/WEBP + replace visual — **xong** (cờ `EDITOR_IMAGE_SEGMENTS_ENABLED`) |

IMAGE giữ `assetId`, duration = slot bị thay, loop thành video khi render. Audio không dịch.

---

## 7–8. Domain + status

Domain riêng, **không** reuse `LiveDownloadJob`. Package theo convention repo (không tách module Maven mới):

| Entity | Bảng |
|---|---|
| `VideoProject` | `video_project` |
| `VideoAsset` | `video_asset` |
| `VideoSegment` | `video_segment` |
| `VideoExportJob` | `video_export_job` |

**Project status:** `CREATED` \| `READY` \| `DELETED` (export status tách riêng).  
**Export status:** `CREATED` → `PREPARING` → `RENDERING` → `FINALIZING` → `COMPLETED` \| `FAILED` \| `CANCELLED`.  
Mutate timeline khi export đang chạy → `EXPORT_ALREADY_RUNNING` (409).

---

## 9. Database (Flyway)

Không sửa `V1`. Migration mới:

| File | Nội dung |
|---|---|
| `V2__create_video_edit_project.sql` | Bảng project editor ban đầu |
| `V3__editor_export_settings.sql` | Export settings |
| `V4__create_video_edit_asset.sql` | Asset |
| `V5__editor_domain_tables.sql` | Domain tables đầy đủ |
| `V6__editor_storage_indexes.sql` | Index / FK / `storage_file_name` |
| `V7__editor_export_quality.sql` | Quality FAST/BALANCED/HIGH |
| `V8__editor_source_storage_mode.sql` | Ownership UPLOAD vs RECORDING_IMPORT |
| `V9__editor_playback_rate.sql` | `video_segment.playback_rate` (visual speed) |

Index: project `created_at`, segment `(project_id, position)`, asset `project_id`, export `project_id` / `status` / `created_at`. Snake_case.

---

## 10. Storage

- Server tự sinh basename (UUID). Client filename chỉ metadata.
- Root: `EDITOR_STORAGE_DIRECTORY` (mặc định `{recordings}/editor`)
- Layout: `editor/{projectId}/source|assets|exports|tmp/`
- `EditorPathResolver` chống path traversal (cùng kiểu `RecordingPathResolver`)
- Upload stream xuống disk, không load cả file vào RAM
- Cap: source **512 MiB** (`EDITOR_MAX_UPLOAD_BYTES`), IMAGE **20 MiB**

---

## 11–15. API

Base: `/api/v1/editor`. Path param mặc định `{id}`; endpoint export dùng `{exportId}`.

| Method | Path | Prompt | Mô tả |
|---|---|---|---|
| `POST` | `/projects` | 11 | Upload MP4 → ffprobe → 1 segment `0..duration` |
| `POST` | `/projects/from-recording/{recordingId}` | 3 | Import recording `COMPLETED` |
| `GET` | `/projects` / `/projects/{id}` | 11 | List / detail |
| `PUT` | `/projects/{id}/segments` | 11 | Save timeline |
| `POST` | `/projects/{id}/segments/{segmentId}/split` | 12 | Split tại `atMillis` (min 100 ms) |
| `PUT` | `/projects/{id}/timeline` | 13 | Reorder `segmentIds` (đủ, không trùng; không tin position client) |
| `POST` | `/projects/{id}/assets/images` | 14 | Upload IMAGE (magic-byte) |
| `PUT` | `/projects/{id}/segments/{segmentId}/visual` | 14 | Replace visual, duration không đổi |
| `POST` | `/projects/{id}/exports` | 15 | Start export (`202`) |
| `PUT` | `/projects/{id}/export` | 15 | Cập nhật settings |
| `GET` | `/exports/{exportId}/events` | 21 | SSE |
| `GET` | `/projects/{id}/events` | 21 | SSE theo project |
| `GET` | `/exports/{exportId}/file` | 22 | Download khi `COMPLETED` |
| `POST` | `/exports/{exportId}/cancel` | 23 | Cancel |
| `GET` | `/projects/{id}/source` | 24 | Range stream nguồn |
| `GET` | `/assets/{assetId}/content` | 24 | Range stream asset |
| `DELETE` | `/projects/{id}` | 28 | Soft-delete |

Body export: `fps`, `resolution`, `videoCodec` (`H264`), `quality`, `keepOriginalAudio` (V1 luôn `true`; `false` bị reject).

---

## 16–19. FFmpeg export / normalize / FPS / resolution

- `ProcessBuilder` argv, không shell, client không truyền args/filter/path
- Filter visual: `trim` + `setpts` + letterbox + `concat=n=N:v=1:a=0`
- Audio: `-map 0:a:0?` — không concat/reorder audio; không `-shortest` để truncate sai
- Không audio → export video-only vẫn OK
- Normalize trước concat: fps, pixel format (`yuv420p`), scale/pad `force_original_aspect_ratio=decrease`, pad center
- FPS `ORIGINAL` = FPS nguồn; `24/25/30/50/60` chỉ đổi video, không `atempo`/`asetrate`
- Resolution preserve orientation; **không upscale**
  - Landscape 1080p → 1920×1080; portrait → 1080×1920
  - 720p: 1280×720 / 720×1280; 540p: 960×540 / 540×960
- Class: `VisualReorderFilterGraph`, `FfmpegVisualReorderService`, `EditorExportPlanner`

---

## 20. Audio (locked)

Không đổi order / tempo / re-time. Timeline từ 0 đến hết nguồn. Copy AAC nếu mux được vào MP4, không thì encode AAC (`EditorAudioCodecPolicy`). Output container MP4.

---

## 21–24. SSE / download / cancel / preview

SSE (`EditorEventHub`): `editor.export.started|progress|finalizing|completed|failed|cancelled`.

Download stream, không load heap; `Content-Disposition` an toàn.

Cancel: gửi `q` rồi destroy / destroyForcibly; temp xóa; không đụng recording FFmpeg.

Preview V1: frontend seek trên `GET .../source` với Range (`Accept-Ranges`, `206`, `Content-Range`) — `RangeResourceSupport`.

---

## 25–26. Security + error code

Path traversal, filename server-generated, magic-byte whitelist, size limit, không expose absolute path / FFmpeg argv ra client, không arbitrary filter, asset phải thuộc project, timeline transactional, cleanup khi create fail.

| Code | HTTP |
|---|---|
| `EDITOR_PROJECT_NOT_FOUND` / `EDITOR_ASSET_NOT_FOUND` / `EDITOR_SEGMENT_NOT_FOUND` / `EXPORT_NOT_FOUND` | 404 |
| `INVALID_EDITOR_FILE` / `INVALID_SPLIT_POSITION` / `INVALID_TIMELINE` / `INVALID_EDITOR_EXPORT` | 400 |
| `EDITOR_UPLOAD_TOO_LARGE` | 413 |
| `EDITOR_PROBE_FAILED` | 422 |
| `INVALID_EDITOR_STATE` / `EXPORT_ALREADY_RUNNING` / `EXPORT_NOT_READY` | 409 |
| `CONCURRENT_EDITOR_LIMIT_EXCEEDED` | 429 |
| `EXPORT_FAILED` / `EDITOR_STORAGE_ERROR` | 500 |
| `MEDIA_EXECUTABLE_MISSING` | 503 |

Không trả stack trace cho FE. `EXPORT_FAILED` / `EDITOR_STORAGE_ERROR` dùng message mặc định.

---

## 27–29. Concurrency / delete / transaction

- `MAX_CONCURRENT_EDITOR_EXPORTS` độc lập recording; vượt → 429 (không unbounded queue)
- Delete chặn khi export active; chỉ xóa file editor sở hữu; import recording không xóa original (`EditorSourceStorageMode`)
- Transaction: create metadata, split, reorder, export job, status transition; file I/O có compensation / `afterCommit`

---

## 30–33. Test / logging / Swagger / docs

**Tests:** validator, planner, filter graph, path resolver, status, error mapping, MockMvc (upload/split/reorder/export/download), Flyway IT, `FfmpegVisualReorderIT` (A-B-C-D → CADB, skip nếu thiếu ffmpeg).

**Log INFO:** `projectId`, `exportId`, status `from`/`to`, progress throttled, duration, metadata, export settings. Không spam frame, không log path/argv/token.

**Swagger:** group/tag **Video Editor**. Prod tắt springdoc.

**Docs đã cập nhật:** README, `.env.example`, `docs/DEPLOYMENT.md`, `doc/CAC_PHAN_DA_LAM.md`, file này.

---

## 34. Cách triển khai (chia class)

Không god-class, không interface thừa. Mapping:

| Vai trò prompt | Class thực tế |
|---|---|
| Project service | `VideoEditorService` |
| Asset service | `EditorAssetService` |
| Timeline service | `EditorTimelineService` |
| Export job service | `VideoEditorRenderService` |
| FFmpeg editor/render | `FfmpegVisualReorderService` |
| Filter graph builder | `VisualReorderFilterGraph` |
| Storage | `EditorPathResolver` (`util`, cùng kiểu recording — không bọc interface) |
| Event hub | `EditorEventHub` |
| Controller | `VideoEditorController` |
| DTO mapper / validation | `SegmentMapper`, `EditorSegmentValidator` |

SSE inject repository trên controller — cùng pattern recording.

---

## 35. Thứ tự thực hiện

Đã làm đúng thứ tự: inspect → architecture FFmpeg/job/storage/error → file list → migration → entity → DTO → project/storage → ffprobe upload → timeline → Range source → export job → filter graph/render → SSE → download → error → tests → Swagger/docs → `mvn test` → fix → summary + curl.

Không dừng ở code mẫu; đã chỉnh repository thật.

---

## 36. Acceptance criteria (prompt gốc 14/08)

| # | Tiêu chí | Kết quả |
|---|---|---|
| 1 | Upload MP4 có video + audio | `POST /projects` |
| 2 | ffprobe thành công | metadata trên project |
| 3 | 1 segment toàn video | `sourceStart=0`, `sourceEnd=duration` |
| 4 | Split 4 đoạn | `POST .../split` |
| 5 | Reorder A-B-C-D → C-A-D-B | `PUT .../timeline` |
| 6 | Save timeline | `PUT .../segments` |
| 7 | Export H.264 | `videoCodec=H264` |
| 8 | Audio gốc 0 → hết (khi output ≈ nguồn) | `-map 0:a:0?`, không reorder |
| 9 | Output duration ≈ input (khi chưa trim/speed) | `-t` = duration nguồn + IT slack |
| 10 | Export 25 FPS | `fps=25` |
| 11 | Export 30 FPS | `fps=30` |
| 12 | SSE progress | `/exports/{exportId}/events` — 100% = `outputDurationMillis` |
| 13 | Download MP4 | `/exports/{exportId}/file` |
| 14 | Range seek source | `GET .../source` + `Range: bytes=0-1` → 206 |
| 15 | Recording API còn chạy | `/api/v1/recordings` không đổi |
| 16 | `mvn test` pass | `.\mvnw.cmd test` exit 0 |
| 17 | App start bình thường | `.\mvnw.cmd spring-boot:run` |

**Acceptance bổ sung 15/08 (mục 35):** undo split (`merge-next`); resize boundary; trim 27.167s → 25s (video+audio); speed 2x visual only; IMAGE replace; livestream không regression. Curl: [README.md](../README.md#editor-acceptance-curl).

Ambiguity → ưu tiên pattern repo hiện có.

---

## Mapping class chính

```text
VideoEditorController  /api/v1/editor
        ├── VideoEditorService          upload / import / get / delete / export settings
        ├── EditorTimelineService       split / reorder / save segments / replace visual
        ├── EditorAssetService          IMAGE + Range streaming
        ├── VideoEditorRenderService    export job + status
        ├── FfmpegVisualReorderService  ProcessBuilder argv
        ├── VisualReorderFilterGraph    trim/concat visual only
        ├── EditorEventHub              SSE
        └── EditorPathResolver          disk paths
```

---

## Config editor (tóm tắt)

| Env | Mặc định | Ý nghĩa |
|---|---|---|
| `EDITOR_STORAGE_DIRECTORY` | `{recordings}/editor` | Root file editor |
| `EDITOR_MAX_UPLOAD_BYTES` | 512 MiB | Cap upload nguồn |
| `EDITOR_MAX_IMAGE_UPLOAD_BYTES` | 20 MiB | Cap IMAGE |
| `MAX_CONCURRENT_EDITOR_EXPORTS` | 2 (dev: 1) | Slot FFmpeg export |
| `EDITOR_EXPORT_TIMEOUT_MINUTES` | 60 | Timeout render |
| `EDITOR_DELETE_TEMP_AFTER_EXPORT` | true | Xóa `tmp/` |
| `EDITOR_IMAGE_SEGMENTS_ENABLED` | false / true (dev) | Phase 1B |

Cần `ffmpeg` + `ffprobe` trên host, encoder `libx264`.

---

## Cách test bằng curl (flow chấp nhận)

Dev: `.\mvnw.cmd spring-boot:run` → `http://localhost:8081`  
Swagger: `http://localhost:8081/swagger-ui/index.html` (tag **Video Editor**)

```bash
# 0) File thử 8s (4 scene màu + audio sine)
ffmpeg -y \
  -f lavfi -i "color=c=red:s=1280x720:d=2,format=yuv420p" \
  -f lavfi -i "color=c=green:s=1280x720:d=2,format=yuv420p" \
  -f lavfi -i "color=c=blue:s=1280x720:d=2,format=yuv420p" \
  -f lavfi -i "color=c=yellow:s=1280x720:d=2,format=yuv420p" \
  -f lavfi -i "sine=frequency=440:duration=8" \
  -filter_complex "[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0[v]" \
  -map "[v]" -map 4:a -c:v libx264 -c:a aac -shortest source.mp4

# 1) Upload
curl -sS -F "file=@source.mp4" -F "name=demo-20260814" \
  http://localhost:8081/api/v1/editor/projects
# Lưu PROJECT_ID, SEGMENT_ID, durationMillis (~8000)

# 2) Split thành 4 (cắt lần lượt 2000, 4000, 6000 trên đoạn VIDEO hiện tại)
curl -sS -X POST \
  http://localhost:8081/api/v1/editor/projects/$PROJECT_ID/segments/$SEG/split \
  -H "Content-Type: application/json" -d '{"atMillis":2000}'
# GET project, lấy 4 id: A B C D

# 3) Reorder visual C-A-D-B
curl -sS -X PUT \
  http://localhost:8081/api/v1/editor/projects/$PROJECT_ID/timeline \
  -H "Content-Type: application/json" \
  -d "{\"segmentIds\":[\"$C\",\"$A\",\"$D\",\"$B\"]}"

# 4) Export H.264, audio gốc, FPS gốc
curl -sS -X POST \
  http://localhost:8081/api/v1/editor/projects/$PROJECT_ID/exports \
  -H "Content-Type: application/json" \
  -d '{"videoCodec":"H264","keepOriginalAudio":true,"quality":"BALANCED"}'
# Lưu EXPORT_ID

# 5) SSE (giữ đến COMPLETED)
curl -N http://localhost:8081/api/v1/editor/exports/$EXPORT_ID/events

# 6) Download + kiểm duration
curl -L -o out.mp4 http://localhost:8081/api/v1/editor/exports/$EXPORT_ID/file
ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 out.mp4

# 7) Export 25 FPS rồi 30 FPS (đợi export trước COMPLETED — max concurrent mặc định 1 ở dev)
curl -sS -X POST .../exports -H "Content-Type: application/json" -d '{"fps":"25","videoCodec":"H264"}'
curl -sS -X POST .../exports -H "Content-Type: application/json" -d '{"fps":"30","videoCodec":"H264"}'

# 8) Range seek
curl -I -H "Range: bytes=0-1" \
  http://localhost:8081/api/v1/editor/projects/$PROJECT_ID/source
# Kỳ vọng: 206, Accept-Ranges: bytes, Content-Range: bytes 0-1/...

# 9) Recording API vẫn sống
curl -sS "http://localhost:8081/api/v1/recordings?page=0&size=5"
```

---

## File chính liên quan ngày hôm nay

**Migration:** `src/main/resources/db/migration/V2` … `V8`

**Java (rút gọn):**  
`VideoEditorController`, `VideoEditorService`, `EditorTimelineService`, `EditorAssetService`, `VideoEditorRenderService`, `FfmpegVisualReorderService`, `VisualReorderFilterGraph`, `EditorEventHub`, `EditorPathResolver`, `SegmentMapper`, `EditorSegmentValidator`, entities/repos editor, DTOs, `ErrorCode` + exceptions editor.

**Test:** `VideoEditorControllerTest`, `VideoEditorServiceTest`, `VisualReorderFilterGraphTest`, `FfmpegVisualReorderIT`, …

**Docs:** README, `.env.example`, `docs/DEPLOYMENT.md`, `doc/CAC_PHAN_DA_LAM.md`, **file này**.

---

*Ghi ngày 14/08/2026. Phản ánh codebase sau khi hoàn thành prompt mục 1–36.*
