package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.config.OpenApiConfig;
import com.vhmedia.livedownloader.dto.request.ReorderEditorTimelineRequest;
import com.vhmedia.livedownloader.dto.request.ReplaceEditorSegmentVisualRequest;
import com.vhmedia.livedownloader.dto.request.ResizeEditorBoundaryRequest;
import com.vhmedia.livedownloader.dto.request.SetEditorOutputRangeRequest;
import com.vhmedia.livedownloader.dto.request.SetEditorSegmentSpeedRequest;
import com.vhmedia.livedownloader.dto.request.SplitEditorSegmentRequest;
import com.vhmedia.livedownloader.dto.request.StartEditorExportRequest;
import com.vhmedia.livedownloader.dto.request.TrimEditorSegmentRequest;
import com.vhmedia.livedownloader.dto.request.UpdateEditorExportRequest;
import com.vhmedia.livedownloader.dto.request.UpdateEditorSegmentsRequest;
import com.vhmedia.livedownloader.dto.response.EditorAssetResponse;
import com.vhmedia.livedownloader.dto.response.EditorOptionsResponse;
import com.vhmedia.livedownloader.dto.response.EditorProjectResponse;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.exception.EditorExportNotFoundException;
import com.vhmedia.livedownloader.exception.EditorProjectNotFoundException;
import com.vhmedia.livedownloader.media.EditorEventHub;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.service.EditorAssetService;
import com.vhmedia.livedownloader.service.EditorTimelineService;
import com.vhmedia.livedownloader.service.VideoEditorRenderService;
import com.vhmedia.livedownloader.service.VideoEditorService;
import com.vhmedia.livedownloader.util.ContentDispositionFilenames;
import com.vhmedia.livedownloader.util.RangeResourceSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/editor")
@Tag(name = OpenApiConfig.TAG_VIDEO_EDITOR, description = "Visual editor. Audio Locked: original[0..outputDuration] — reorder/speed do not move audio; output-range trims audio to the new length.")
public class VideoEditorController {

	private final VideoEditorService videoEditorService;
	private final VideoEditorRenderService videoEditorRenderService;
	private final EditorTimelineService editorTimelineService;
	private final EditorAssetService editorAssetService;
	private final EditorEventHub editorEventHub;
	private final VideoProjectRepository projectRepository;
	private final VideoExportJobRepository exportJobRepository;

	public VideoEditorController(
			VideoEditorService videoEditorService,
			VideoEditorRenderService videoEditorRenderService,
			EditorTimelineService editorTimelineService,
			EditorAssetService editorAssetService,
			EditorEventHub editorEventHub,
			VideoProjectRepository projectRepository,
			VideoExportJobRepository exportJobRepository
	) {
		this.videoEditorService = videoEditorService;
		this.videoEditorRenderService = videoEditorRenderService;
		this.editorTimelineService = editorTimelineService;
		this.editorAssetService = editorAssetService;
		this.editorEventHub = editorEventHub;
		this.projectRepository = projectRepository;
		this.exportJobRepository = exportJobRepository;
	}

	@GetMapping("/options")
	@Operation(summary = "Export presets", description = "Allowed fps, resolution, codec, quality. V1 keepOriginalAudio is always true.")
	public ResponseEntity<EditorOptionsResponse> options() {
		return ResponseEntity.ok(videoEditorService.options());
	}

	@PostMapping(path = "/projects", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(
			summary = "Upload source MP4",
			description = """
					Creates a READY project from a local MP4. Size cap: `EDITOR_MAX_UPLOAD_BYTES` (default 512 MiB). \
					Magic-byte `ftyp` check; Content-Type is a first-pass whitelist only. \
					The original audio timeline is stored as-is and never edited in V1.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Project created"),
			@ApiResponse(responseCode = "400", description = "INVALID_EDITOR_FILE / VALIDATION_FAILED"),
			@ApiResponse(responseCode = "413", description = "EDITOR_UPLOAD_TOO_LARGE"),
			@ApiResponse(responseCode = "422", description = "EDITOR_PROBE_FAILED")
	})
	public ResponseEntity<EditorProjectResponse> createFromUpload(
			@Parameter(description = "Source MP4", required = true)
			@RequestParam("file") MultipartFile file,
			@Parameter(description = "Optional display name")
			@RequestParam(value = "name", required = false) @Size(max = 255) String name,
			@Parameter(description = "Legacy alias for name")
			@RequestParam(value = "title", required = false) @Size(max = 255) String title
	) {
		EditorProjectResponse response = videoEditorService.createFromUpload(file, firstNonBlank(name, title));
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/projects/from-recording/{recordingId}")
	@Operation(summary = "Create project from a COMPLETED recording", description = "Hard-links the MP4 when possible; falls back to copy. Recording original is never owned by the editor.")
	public ResponseEntity<EditorProjectResponse> createFromRecording(
			@PathVariable("recordingId") UUID recordingId,
			@RequestParam(value = "name", required = false) @Size(max = 255) String name
	) {
		EditorProjectResponse response = videoEditorService.createFromRecording(recordingId, name);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/projects")
	@Operation(summary = "List projects")
	public ResponseEntity<Page<EditorProjectResponse>> list(
			@RequestParam(required = false) ProjectStatus status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		return ResponseEntity.ok(videoEditorService.list(status, pageable));
	}

	@GetMapping("/projects/{id}")
	@Operation(
			summary = "Get project",
			description = """
					Timeline, source metadata, and latest export status. Path param is `{id}`. \
					`sourceDurationMillis` is the probed source length. \
					`outputDurationMillis` (alias `visualDurationMillis`) is `sum(segment.visualDurationMillis)` after speed. \
					Do not treat `durationMillis` as output length (compat alias of source). \
					`timelineVersion` is the optimistic lock token for subsequent mutations. \
					Each segment includes `canMergeNext`, `canResizeRightBoundary`, and `canResizeLeftBoundary`.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Project"),
			@ApiResponse(responseCode = "404", description = "EDITOR_PROJECT_NOT_FOUND")
	})
	public ResponseEntity<EditorProjectResponse> get(@PathVariable("id") UUID id) {
		return ResponseEntity.ok(videoEditorService.get(id));
	}

	@PutMapping("/projects/{id}/segments")
	@Operation(
			summary = "Replace visual segments",
			description = "Full visual partition of the source. Audio is not in this payload and stays source[0..outputDuration]. Optional `timelineVersion`."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_TIMELINE / VALIDATION_FAILED"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> updateSegments(
			@PathVariable("id") UUID id,
			@Valid @RequestBody UpdateEditorSegmentsRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.updateSegments(id, request));
	}

	@PostMapping("/projects/{id}/segments/{segmentId}/split")
	@Operation(
			summary = "Split a VIDEO segment",
			description = "Cuts at `atMillis` on the source timeline. Left piece keeps the original id. Each piece must be ≥ `EDITOR_MIN_SEGMENT_DURATION_MS`. IMAGE segments cannot be split."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_SPLIT_POSITION / SEGMENT_TOO_SHORT / VALIDATION_FAILED"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING / INVALID_EDITOR_STATE")
	})
	public ResponseEntity<EditorProjectResponse> splitSegment(
			@PathVariable("id") UUID id,
			@PathVariable("segmentId") UUID segmentId,
			@Valid @RequestBody SplitEditorSegmentRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.splitSegment(id, segmentId, request));
	}

	@PostMapping("/projects/{id}/segments/{segmentId}/merge-next")
	@Operation(
			summary = "Merge with next clip (undo split)",
			description = "Joins this VIDEO clip with the next *visual* neighbor when they share the same source cut. "
					+ "This is the deterministic undo of split (left id is kept). Metadata only — no new media file. "
					+ "Both clips must be VIDEO, same asset, same playback rate, source-contiguous, and adjacent on the timeline. "
					+ "After reorder `A1 | B | A2`, A1 cannot merge with B. Reorder back to `A1 | A2 | B` and merge works again. "
					+ "Optional query `timelineVersion` must match `project.timelineVersion` or the call returns TIMELINE_CONFLICT."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "404", description = "EDITOR_SEGMENT_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "SEGMENTS_NOT_MERGEABLE / TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> mergeNext(
			@PathVariable("id") UUID id,
			@PathVariable("segmentId") UUID segmentId,
			@Parameter(description = "If set, must match project.timelineVersion or TIMELINE_CONFLICT (409).")
			@RequestParam(value = "timelineVersion", required = false) Long timelineVersion
	) {
		return ResponseEntity.ok(editorTimelineService.mergeNext(id, segmentId, timelineVersion));
	}

	@PutMapping("/projects/{id}/segments/{segmentId}/boundary")
	@Operation(
			summary = "Move the cut between this clip and the next",
			description = "`boundaryMillis` becomes left sourceEnd and right sourceStart. No gap, no overlap. "
					+ "Neighbors must be source-contiguous VIDEO clips on the visual timeline "
					+ "(after reorder `C 20..30 | A 0..10`, the shared handle is disabled — see `canResizeRightBoundary`). "
					+ "Each piece stays ≥ `EDITOR_MIN_SEGMENT_DURATION_MS`."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_SEGMENT_BOUNDARY / SEGMENT_TOO_SHORT / VALIDATION_FAILED"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> resizeBoundary(
			@PathVariable("id") UUID id,
			@PathVariable("segmentId") UUID segmentId,
			@Valid @RequestBody ResizeEditorBoundaryRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.resizeBoundary(id, segmentId, request));
	}

	@PutMapping("/projects/{id}/output-range")
	@Operation(
			summary = "Crop the visual output range",
			description = "Canonical project-level trim. `startMillis`/`endMillis` are on the current visual output "
					+ "(not a second stored window). Example: 27.167s → 25.000s is `{ startMillis: 0, endMillis: 25000 }`. "
					+ "Does not expand. **Audio is trimmed to original[0..newOutput]** (not reordered, not sped). "
					+ "Clip-edge `PUT .../segments/{segmentId}/trim` is only for first/last source handles; both write segments."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_SEGMENT_TRIM / SEGMENT_TOO_SHORT / INVALID_OUTPUT_DURATION / VALIDATION_FAILED"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> setOutputRange(
			@PathVariable("id") UUID id,
			@Valid @RequestBody SetEditorOutputRangeRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.setOutputRange(id, request));
	}

	@PutMapping("/projects/{id}/segments/{segmentId}/trim")
	@Operation(
			summary = "Trim first/last clip edges",
			description = "Shorten the left edge of the first clip and/or the right edge of the last clip. "
					+ "Prefer `PUT .../output-range` to set the whole output to 25.000s. "
					+ "Shared cuts use PUT .../boundary. Each clip stays ≥ `EDITOR_MIN_SEGMENT_DURATION_MS`."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_SEGMENT_TRIM / SEGMENT_TOO_SHORT / VALIDATION_FAILED"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> trimSegment(
			@PathVariable("id") UUID id,
			@PathVariable("segmentId") UUID segmentId,
			@Valid @RequestBody TrimEditorSegmentRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.trimSegment(id, segmentId, request));
	}

	@PutMapping("/projects/{id}/segments/{segmentId}/speed")
	@Operation(
			summary = "Set visual playback speed",
			description = "Whitelist: 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0. "
					+ "Applies `setpts=(PTS-STARTPTS)/rate` to this VIDEO clip only. "
					+ "Audio stays original[0..outputDuration] — no atempo. "
					+ "Slow-motion that would make output longer than the original audio is rejected "
					+ "(`OUTPUT_DURATION_EXCEEDS_AUDIO`). IMAGE clips reject speed (`PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE`)."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_PLAYBACK_RATE / PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE / OUTPUT_DURATION_EXCEEDS_AUDIO / SEGMENT_TOO_SHORT"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> setSegmentSpeed(
			@PathVariable("id") UUID id,
			@PathVariable("segmentId") UUID segmentId,
			@Valid @RequestBody SetEditorSegmentSpeedRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.setSegmentSpeed(id, segmentId, request));
	}

	@PostMapping("/projects/{id}/segments/{segmentId}/reset")
	@Operation(
			summary = "Reset clip speed / restore original video",
			description = "Sets `playbackRate` to 1.0. IMAGE clips with a stored source slot become the original VIDEO "
					+ "for that range. Does **not** restore trimmed source bounds (not stored — that would be a false reset). "
					+ "Undo split with POST .../merge-next."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_TIMELINE / OUTPUT_DURATION_EXCEEDS_AUDIO"),
			@ApiResponse(responseCode = "404", description = "EDITOR_SEGMENT_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> resetSegment(
			@PathVariable("id") UUID id,
			@PathVariable("segmentId") UUID segmentId,
			@Parameter(description = "If set, must match project.timelineVersion or TIMELINE_CONFLICT (409).")
			@RequestParam(value = "timelineVersion", required = false) Long timelineVersion
	) {
		return ResponseEntity.ok(editorTimelineService.resetSegment(id, segmentId, timelineVersion));
	}

	@PutMapping("/projects/{id}/timeline")
	@Operation(
			summary = "Reorder visual timeline",
			description = "`segmentIds` must list every project segment exactly once. Visual order changes; audio is never reordered (still original[0..outputDuration])."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "400", description = "INVALID_TIMELINE / VALIDATION_FAILED"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING")
	})
	public ResponseEntity<EditorProjectResponse> reorderTimeline(
			@PathVariable("id") UUID id,
			@Valid @RequestBody ReorderEditorTimelineRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.reorderTimeline(id, request));
	}

	@PutMapping("/projects/{id}/segments/{segmentId}/visual")
	@Operation(
			summary = "Replace segment visual with an IMAGE",
			description = "Phase 1B. Hold duration is the current visual slot (after speed). Audio stays original[0..outputDuration]. Optional `timelineVersion`."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated timeline"),
			@ApiResponse(responseCode = "409", description = "TIMELINE_CONFLICT / EXPORT_ALREADY_RUNNING / INVALID_EDITOR_STATE")
	})
	public ResponseEntity<EditorProjectResponse> replaceSegmentVisual(
			@PathVariable("id") UUID id,
			@PathVariable("segmentId") UUID segmentId,
			@Valid @RequestBody ReplaceEditorSegmentVisualRequest request
	) {
		return ResponseEntity.ok(editorTimelineService.replaceSegmentVisual(id, segmentId, request));
	}

	@PostMapping(path = "/projects/{id}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Upload IMAGE asset", description = "JPEG/PNG/WEBP. Cap: `EDITOR_MAX_IMAGE_UPLOAD_BYTES` (default 20 MiB). Requires `EDITOR_IMAGE_SEGMENTS_ENABLED`.")
	public ResponseEntity<EditorAssetResponse> uploadAsset(
			@PathVariable("id") UUID id,
			@RequestParam("file") MultipartFile file
	) {
		return ResponseEntity.status(HttpStatus.CREATED).body(editorAssetService.addImage(id, file));
	}

	@PostMapping(path = "/projects/{id}/assets/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Upload IMAGE asset (alias)")
	public ResponseEntity<EditorAssetResponse> uploadImageAsset(
			@PathVariable("id") UUID id,
			@RequestParam("file") MultipartFile file
	) {
		return ResponseEntity.status(HttpStatus.CREATED).body(editorAssetService.addImage(id, file));
	}

	@GetMapping("/projects/{id}/assets")
	@Operation(summary = "List project assets")
	public ResponseEntity<List<EditorAssetResponse>> listAssets(@PathVariable("id") UUID id) {
		return ResponseEntity.ok(editorAssetService.list(id));
	}

	@DeleteMapping("/projects/{id}/assets/{assetId}")
	@Operation(summary = "Delete an IMAGE asset")
	public ResponseEntity<Void> deleteAsset(
			@PathVariable("id") UUID id,
			@PathVariable("assetId") UUID assetId
	) {
		editorAssetService.delete(id, assetId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/projects/{id}/assets/{assetId}/file")
	@Operation(summary = "Download an asset file")
	public ResponseEntity<Resource> downloadAsset(
			@PathVariable("id") UUID id,
			@PathVariable("assetId") UUID assetId
	) {
		EditorAssetService.AssetFileDownload download = editorAssetService.getFile(id, assetId);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionFilenames.inline(download.filename()))
				.contentType(parseMediaType(download.contentType()))
				.contentLength(download.contentLength())
				.body(download.resource());
	}

	@RequestMapping(path = "/projects/{id}/source", method = {RequestMethod.GET, RequestMethod.HEAD})
	@Operation(summary = "Stream original source MP4", description = "HTTP Range (`Accept-Ranges`, `206`). Used by the browser preview; V1 does not render a preview file.")
	public ResponseEntity<Resource> projectSource(
			@PathVariable("id") UUID id,
			@RequestHeader HttpHeaders headers
	) {
		EditorAssetService.AssetFileDownload download = editorAssetService.getPrimarySource(id);
		return RangeResourceSupport.toResponse(
				download.resource(),
				download.contentLength(),
				parseMediaType(download.contentType()),
				ContentDispositionFilenames.inline(download.filename()),
				headers
		);
	}

	@RequestMapping(path = "/assets/{assetId}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
	@Operation(summary = "Stream any editor asset", description = "HTTP Range. Path param is `{assetId}`.")
	public ResponseEntity<Resource> assetContent(
			@PathVariable("assetId") UUID assetId,
			@RequestHeader HttpHeaders headers
	) {
		EditorAssetService.AssetFileDownload download = editorAssetService.getContent(assetId);
		return RangeResourceSupport.toResponse(
				download.resource(),
				download.contentLength(),
				parseMediaType(download.contentType()),
				ContentDispositionFilenames.inline(download.filename()),
				headers
		);
	}

	@PutMapping("/projects/{id}/export")
	@Operation(summary = "Update export settings", description = "fps ORIGINAL/24/25/30/50/60, resolution ORIGINAL/1080p/720p/540p, codec H264, quality FAST/BALANCED/HIGH. Audio is always original.")
	public ResponseEntity<EditorProjectResponse> updateExport(
			@PathVariable("id") UUID id,
			@Valid @RequestBody UpdateEditorExportRequest request
	) {
		return ResponseEntity.ok(videoEditorService.updateExportSettings(id, request));
	}

	@PostMapping("/projects/{id}/render")
	@Operation(summary = "Start export (legacy path)", description = "Alias of POST /projects/{id}/exports.")
	public ResponseEntity<EditorProjectResponse> render(
			@PathVariable("id") UUID id,
			@RequestBody(required = false) UpdateEditorExportRequest exportRequest
	) {
		EditorProjectResponse response = videoEditorRenderService.startRender(id, exportRequest);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
	}

	@PostMapping("/projects/{id}/exports")
	@Operation(
			summary = "Start export",
			description = """
					Queues an FFmpeg visual-reorder render (H.264 MP4). Visual uses trim+setpts; \
					audio is original[0..outputDuration] (`atrim` when shorter than source, else `-map 0:a:0?`). \
					No `atempo`/`asetrate`/`-shortest`. Concurrent exports: `MAX_CONCURRENT_EDITOR_EXPORTS`. \
					Clients cannot pass raw FFmpeg args.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Export PREPARING"),
			@ApiResponse(responseCode = "400", description = "INVALID_EDITOR_EXPORT / INVALID_TIMELINE"),
			@ApiResponse(responseCode = "409", description = "EXPORT_ALREADY_RUNNING / INVALID_EDITOR_STATE"),
			@ApiResponse(responseCode = "429", description = "CONCURRENT_EDITOR_LIMIT_EXCEEDED")
	})
	public ResponseEntity<EditorProjectResponse> startExport(
			@PathVariable("id") UUID id,
			@Valid @RequestBody(required = false) StartEditorExportRequest exportRequest
	) {
		UpdateEditorExportRequest update = exportRequest == null ? null : exportRequest.toUpdateRequest();
		EditorProjectResponse response = videoEditorRenderService.startRender(id, update);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
	}

	@PostMapping("/projects/{id}/cancel")
	@Operation(summary = "Cancel the project's active export")
	public ResponseEntity<EditorProjectResponse> cancel(@PathVariable("id") UUID id) {
		EditorProjectResponse response = videoEditorRenderService.requestCancel(id);
		return ResponseEntity.accepted().body(response);
	}

	@PostMapping("/exports/{exportId}/cancel")
	@Operation(summary = "Cancel export by id", description = "Path param is `{exportId}`. Graceful FFmpeg `q`, then destroy. Recording processes are not touched.")
	public ResponseEntity<EditorProjectResponse> cancelExport(@PathVariable("exportId") UUID exportId) {
		EditorProjectResponse response = videoEditorRenderService.requestCancelExport(exportId);
		return ResponseEntity.accepted().body(response);
	}

	@DeleteMapping("/projects/{id}")
	@Operation(summary = "Soft-delete project", description = "Blocked while an export is active. Removes editor-owned files only; never deletes a recording original.")
	public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
		videoEditorService.delete(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/projects/{id}/file")
	@Operation(summary = "Download latest COMPLETED export for the project")
	@ApiResponse(responseCode = "409", description = "EXPORT_NOT_READY")
	public ResponseEntity<Resource> download(@PathVariable("id") UUID id) {
		VideoEditorService.EditorFileDownload download = videoEditorService.getDownload(id);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionFilenames.attachment(download.filename()))
				.contentType(MediaType.parseMediaType("video/mp4"))
				.contentLength(download.contentLength())
				.body(download.resource());
	}

	@GetMapping("/exports/{exportId}/file")
	@Operation(
			summary = "Download export MP4",
			description = "COMPLETED only. Streamed attachment (`video/mp4`). Path param is `{exportId}`."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "MP4", content = @Content(mediaType = "video/mp4")),
			@ApiResponse(responseCode = "404", description = "EXPORT_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "EXPORT_NOT_READY")
	})
	public ResponseEntity<Resource> downloadExport(@PathVariable("exportId") UUID exportId) {
		VideoEditorService.EditorFileDownload download = videoEditorService.getExportDownload(exportId);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionFilenames.attachment(download.filename()))
				.contentType(MediaType.parseMediaType("video/mp4"))
				.contentLength(download.contentLength())
				.body(download.resource());
	}

	@GetMapping(path = "/projects/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@Operation(
			summary = "Project export SSE",
			description = "Same event names as `/exports/{exportId}/events` for the project's latest export. Snapshot on subscribe."
	)
	@ApiResponse(responseCode = "200", description = "text/event-stream", content = @Content(mediaType = "text/event-stream"))
	public SseEmitter events(@PathVariable("id") UUID id) {
		projectRepository.findById(id)
				.filter(project -> project.getStatus() != ProjectStatus.DELETED)
				.orElseThrow(() -> new EditorProjectNotFoundException("Editor project not found: " + id));
		return editorEventHub.subscribe(id);
	}

	@GetMapping(path = "/exports/{exportId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@Operation(
			summary = "Export SSE events",
			description = """
					`editor.export.started` | `progress` | `finalizing` | `completed` | `failed` | `cancelled`. \
					Payload: exportId, projectId, status, processedMillis, durationMillis, progressPercent, fps, speed. \
					`durationMillis` / 100% is `outputDurationMillis` (not source length). Example: source 27s trimmed to 25s → 100% at 25s. \
					Path param is `{exportId}`.
					"""
	)
	@ApiResponse(responseCode = "200", description = "text/event-stream", content = @Content(mediaType = "text/event-stream"))
	public SseEmitter exportEvents(@PathVariable("exportId") UUID exportId) {
		exportJobRepository.findById(exportId)
				.orElseThrow(() -> new EditorExportNotFoundException("Editor export not found: " + exportId));
		return editorEventHub.subscribeExport(exportId);
	}

	private static String firstNonBlank(String name, String title) {
		if (name != null && !name.isBlank()) {
			return name;
		}
		if (title != null && !title.isBlank()) {
			return title;
		}
		return null;
	}

	private static MediaType parseMediaType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
		try {
			return MediaType.parseMediaType(contentType);
		} catch (RuntimeException ex) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}
}
