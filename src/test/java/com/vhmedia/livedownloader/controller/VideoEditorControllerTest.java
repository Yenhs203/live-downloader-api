package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.dto.request.UpdateEditorExportRequest;
import com.vhmedia.livedownloader.dto.response.EditorAssetResponse;
import com.vhmedia.livedownloader.dto.response.EditorExportResponse;
import com.vhmedia.livedownloader.dto.response.EditorProjectResponse;
import com.vhmedia.livedownloader.dto.response.EditorSegmentResponse;
import com.vhmedia.livedownloader.enums.AssetType;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.enums.VideoEditSourceType;
import com.vhmedia.livedownloader.exception.ConcurrentEditorLimitException;
import com.vhmedia.livedownloader.exception.EditorExportNotFoundException;
import com.vhmedia.livedownloader.exception.EditorProjectNotFoundException;
import com.vhmedia.livedownloader.exception.ExportNotReadyException;
import com.vhmedia.livedownloader.exception.GlobalExceptionHandler;
import com.vhmedia.livedownloader.exception.InvalidEditorExportException;
import com.vhmedia.livedownloader.exception.InvalidSegmentBoundaryException;
import com.vhmedia.livedownloader.exception.PlaybackRateNotSupportedForImageException;
import com.vhmedia.livedownloader.exception.SegmentsNotMergeableException;
import com.vhmedia.livedownloader.exception.TimelineConflictException;
import com.vhmedia.livedownloader.media.EditorEventHub;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.service.EditorAssetService;
import com.vhmedia.livedownloader.service.EditorTimelineService;
import com.vhmedia.livedownloader.service.VideoEditorRenderService;
import com.vhmedia.livedownloader.service.VideoEditorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VideoEditorController.class)
@Import(GlobalExceptionHandler.class)
class VideoEditorControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private VideoEditorService videoEditorService;

	@MockitoBean
	private VideoEditorRenderService videoEditorRenderService;

	@MockitoBean
	private EditorTimelineService editorTimelineService;

	@MockitoBean
	private EditorEventHub editorEventHub;

	@MockitoBean
	private EditorAssetService editorAssetService;

	@MockitoBean
	private VideoProjectRepository projectRepository;

	@MockitoBean
	private VideoExportJobRepository exportJobRepository;

	@Test
	void createFromRecordingReturnsCreated() throws Exception {
		UUID id = UUID.randomUUID();
		UUID recordingId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		when(videoEditorService.createFromRecording(eq(recordingId), eq("Cut 1")))
				.thenReturn(sample(id, ProjectStatus.READY).toBuilder().sourceAssetId(sourceAssetId).build());

		mockMvc.perform(post("/api/v1/editor/projects/from-recording/" + recordingId)
						.param("name", "Cut 1"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.name").value("Cut 1"))
				.andExpect(jsonPath("$.sourceAssetId").value(sourceAssetId.toString()))
				.andExpect(jsonPath("$.hasAudio").value(true))
				.andExpect(jsonPath("$.segments[0].type").value("VIDEO"))
				.andExpect(jsonPath("$.segments[0].position").value(0));
	}

	@Test
	void getProjectReturnsReadyTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorService.get(id)).thenReturn(sample(id, ProjectStatus.READY));

		mockMvc.perform(get("/api/v1/editor/projects/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.hasAudio").value(true))
				.andExpect(jsonPath("$.sourceDurationMillis").value(4000))
				.andExpect(jsonPath("$.outputDurationMillis").value(4000))
				.andExpect(jsonPath("$.timelineVersion").value(0))
				.andExpect(jsonPath("$.segments[0].type").value("VIDEO"))
				.andExpect(jsonPath("$.segments[0].sourceDurationMillis").value(4000))
				.andExpect(jsonPath("$.segments[0].visualDurationMillis").value(4000))
				.andExpect(jsonPath("$.segments[0].playbackRate").value(1.0))
				.andExpect(jsonPath("$.segments[0].canMergeNext").value(false))
				.andExpect(jsonPath("$.segments[0].canResizeRightBoundary").value(false))
				.andExpect(jsonPath("$.segments[0].canResizeLeftBoundary").value(false));
	}

	@Test
	void getProjectReturnsResizeFlagsForSplitNeighbors() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorService.get(id)).thenReturn(
				sample(id, ProjectStatus.READY).toBuilder()
						.segments(List.of(
								EditorSegmentResponse.builder()
										.id("1")
										.label("A")
										.type(EditorSegmentType.VIDEO)
										.sourceStartMillis(0L)
										.sourceEndMillis(2000L)
										.durationMillis(2000)
										.position(0)
										.canMergeNext(true)
										.canResizeRightBoundary(true)
										.canResizeLeftBoundary(false)
										.build(),
								EditorSegmentResponse.builder()
										.id("2")
										.label("B")
										.type(EditorSegmentType.VIDEO)
										.sourceStartMillis(2000L)
										.sourceEndMillis(4000L)
										.durationMillis(2000)
										.position(1)
										.canMergeNext(false)
										.canResizeRightBoundary(false)
										.canResizeLeftBoundary(true)
										.build()
						))
						.build()
		);

		mockMvc.perform(get("/api/v1/editor/projects/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].canMergeNext").value(true))
				.andExpect(jsonPath("$.segments[0].canResizeRightBoundary").value(true))
				.andExpect(jsonPath("$.segments[0].canResizeLeftBoundary").value(false))
				.andExpect(jsonPath("$.segments[1].canMergeNext").value(false))
				.andExpect(jsonPath("$.segments[1].canResizeRightBoundary").value(false))
				.andExpect(jsonPath("$.segments[1].canResizeLeftBoundary").value(true));
	}

	@Test
	void createFromUploadReturnsCreated() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorService.createFromUpload(any(), eq("Upload")))
				.thenReturn(sample(id, ProjectStatus.READY));

		MockMultipartFile file = new MockMultipartFile(
				"file",
				"clip.mp4",
				"video/mp4",
				new byte[]{1, 2, 3, 4}
		);

		mockMvc.perform(multipart("/api/v1/editor/projects")
						.file(file)
						.param("name", "Upload"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("READY"));
	}

	@Test
	void updateSegmentsReturnsVisualOrder() throws Exception {
		UUID id = UUID.randomUUID();
		when(editorTimelineService.updateSegments(eq(id), any())).thenReturn(reordered(id));

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "segments": [
								    { "sourceStartMillis": 2000, "sourceEndMillis": 4000 },
								    { "sourceStartMillis": 0, "sourceEndMillis": 2000 }
								  ]
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].label").value("B"))
				.andExpect(jsonPath("$.segments[0].sourceStartMillis").value(2000))
				.andExpect(jsonPath("$.segments[0].sourceEndMillis").value(4000))
				.andExpect(jsonPath("$.segments[0].durationMillis").value(2000))
				.andExpect(jsonPath("$.segments[0].type").value("VIDEO"))
				.andExpect(jsonPath("$.segments[1].label").value("A"));
	}

	@Test
	void uploadWithoutFileReturnsBadRequest() throws Exception {
		mockMvc.perform(multipart("/api/v1/editor/projects").param("name", "Upload"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void splitWithoutAtMillisReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/split")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void reorderWithEmptySegmentIdsReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/timeline")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "segmentIds": [] }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void exportWithUnsupportedFpsReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorRenderService.startRender(eq(id), any()))
				.thenThrow(new InvalidEditorExportException("Unsupported export fps: 29.97 (allowed: ORIGINAL, 24, 25, 30, 50, 60)"));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/exports")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "fps": "29.97", "keepOriginalAudio": true }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EDITOR_EXPORT"));
	}

	@Test
	void splitSegmentReturnsTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.splitSegment(eq(id), eq(segmentId), any())).thenReturn(split(id, segmentId));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/split")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "atMillis": 12000 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].id").value(segmentId.toString()))
				.andExpect(jsonPath("$.segments[0].sourceStartMillis").value(0))
				.andExpect(jsonPath("$.segments[0].sourceEndMillis").value(12000))
				.andExpect(jsonPath("$.segments[0].durationMillis").value(12000))
				.andExpect(jsonPath("$.segments[0].position").value(0))
				.andExpect(jsonPath("$.segments[1].sourceStartMillis").value(12000))
				.andExpect(jsonPath("$.segments[1].sourceEndMillis").value(30000))
				.andExpect(jsonPath("$.segments[1].position").value(1));
	}

	@Test
	void splitStaleTimelineVersionReturnsConflict() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.splitSegment(eq(id), eq(segmentId), any()))
				.thenThrow(new TimelineConflictException(
						"Timeline was updated by another request. Reload the project and retry."
				));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/split")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "atMillis": 12000, "timelineVersion": 0 }
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TIMELINE_CONFLICT"))
				.andExpect(jsonPath("$.message").value("Timeline was updated by another request. Reload the project and retry."));
	}

	@Test
	void mergeNextReturnsTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.mergeNext(eq(id), eq(segmentId), isNull())).thenReturn(sample(id, ProjectStatus.READY));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/merge-next"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].sourceStartMillis").value(0))
				.andExpect(jsonPath("$.segments[0].sourceEndMillis").value(4000));
	}

	@Test
	void mergeNextNotMergeableReturnsConflict() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.mergeNext(eq(id), eq(segmentId), isNull()))
				.thenThrow(new SegmentsNotMergeableException(
						"These clips do not meet at the same cut, so they cannot be merged."
				));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/merge-next"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEGMENTS_NOT_MERGEABLE"))
				.andExpect(jsonPath("$.message").value("These clips do not meet at the same cut, so they cannot be merged."));
	}

	@Test
	void resizeBoundaryReturnsTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.resizeBoundary(eq(id), eq(segmentId), any())).thenReturn(reordered(id));

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/boundary")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "boundaryMillis": 6000 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].position").value(0))
				.andExpect(jsonPath("$.segments[1].position").value(1));
	}

	@Test
	void resizeBoundaryWithoutMillisReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/boundary")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void resizeBoundaryWhenNeighborsDoNotShareSourceCutReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.resizeBoundary(eq(id), eq(segmentId), any()))
				.thenThrow(new InvalidSegmentBoundaryException(
						"These clips do not share a source cut, so the boundary cannot be dragged."
				));

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/boundary")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "boundaryMillis": 25000 }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SEGMENT_BOUNDARY"));
	}

	@Test
	void setOutputRangeReturnsTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		when(editorTimelineService.setOutputRange(eq(id), any())).thenReturn(
				sample(id, ProjectStatus.READY).toBuilder()
						.durationMillis(27_167L)
						.sourceDurationMillis(27_167L)
						.outputDurationMillis(25_000L)
						.visualDurationMillis(25_000L)
						.segments(List.of(EditorSegmentResponse.builder()
								.id(UUID.randomUUID().toString())
								.label("A")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(0L)
								.sourceEndMillis(25_000L)
								.durationMillis(25_000)
								.position(0)
								.build()))
						.build()
		);

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/output-range")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "startMillis": 0, "endMillis": 25000 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceDurationMillis").value(27167))
				.andExpect(jsonPath("$.outputDurationMillis").value(25000))
				.andExpect(jsonPath("$.durationMillis").value(27167))
				.andExpect(jsonPath("$.segments[0].sourceEndMillis").value(25000));
	}

	@Test
	void setOutputRangeWithoutMillisReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/output-range")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void trimSegmentReturnsTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.trimSegment(eq(id), eq(segmentId), any())).thenReturn(
				sample(id, ProjectStatus.READY).toBuilder()
						.durationMillis(27_167L)
						.sourceDurationMillis(27_167L)
						.outputDurationMillis(25_000L)
						.visualDurationMillis(25_000L)
						.segments(List.of(EditorSegmentResponse.builder()
								.id(segmentId.toString())
								.label("A")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(0L)
								.sourceEndMillis(25_000L)
								.durationMillis(25_000)
								.position(0)
								.build()))
						.build()
		);

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/trim")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "sourceStartMillis": 0, "sourceEndMillis": 25000 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceDurationMillis").value(27167))
				.andExpect(jsonPath("$.outputDurationMillis").value(25000))
				.andExpect(jsonPath("$.durationMillis").value(27167))
				.andExpect(jsonPath("$.segments[0].sourceEndMillis").value(25000));
	}

	@Test
	void setSegmentSpeedReturnsTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.setSegmentSpeed(eq(id), eq(segmentId), any())).thenReturn(
				sample(id, ProjectStatus.READY).toBuilder()
						.durationMillis(10_000L)
						.sourceDurationMillis(10_000L)
						.outputDurationMillis(5_000L)
						.visualDurationMillis(5_000L)
						.segments(List.of(EditorSegmentResponse.builder()
								.id(segmentId.toString())
								.label("A")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(0L)
								.sourceEndMillis(10_000L)
								.durationMillis(5_000)
								.playbackRate(2.0d)
								.position(0)
								.build()))
						.build()
		);

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/speed")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "playbackRate": 2.0 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outputDurationMillis").value(5000))
				.andExpect(jsonPath("$.sourceDurationMillis").value(10000))
				.andExpect(jsonPath("$.segments[0].playbackRate").value(2.0))
				.andExpect(jsonPath("$.segments[0].durationMillis").value(5000));
	}

	@Test
	void setSegmentSpeedWithoutRateReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/speed")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void setSegmentSpeedOnImageReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.setSegmentSpeed(eq(id), eq(segmentId), any()))
				.thenThrow(new PlaybackRateNotSupportedForImageException());

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/speed")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "playbackRate": 1.5 }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE"));
	}

	@Test
	void resetSegmentReturnsTimeline() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		when(editorTimelineService.resetSegment(eq(id), eq(segmentId), isNull())).thenReturn(
				sample(id, ProjectStatus.READY).toBuilder()
						.segments(List.of(EditorSegmentResponse.builder()
								.id(segmentId.toString())
								.label("A")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(0L)
								.sourceEndMillis(10_000L)
								.durationMillis(10_000)
								.playbackRate(1.0d)
								.position(0)
								.build()))
						.build()
		);

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/reset"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].playbackRate").value(1.0));
	}

	@Test
	void trimWithoutRangeReturnsBadRequest() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/trim")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void reorderTimelineReturnsSortedOrder() throws Exception {
		UUID id = UUID.randomUUID();
		when(editorTimelineService.reorderTimeline(eq(id), any())).thenReturn(reordered(id));

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/timeline")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "segmentIds": ["%s", "%s"]
								}
								""".formatted(UUID.randomUUID(), UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].label").value("B"))
				.andExpect(jsonPath("$.segments[0].position").value(0))
				.andExpect(jsonPath("$.segments[1].label").value("A"))
				.andExpect(jsonPath("$.segments[1].position").value(1));
	}

	@Test
	void uploadImageAssetReturnsCreated() throws Exception {
		UUID projectId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		when(editorAssetService.addImage(eq(projectId), any())).thenReturn(
				EditorAssetResponse.builder()
						.id(assetId)
						.type(AssetType.IMAGE)
						.mimeType("image/png")
						.contentType("image/png")
						.byteSize(12)
						.primarySource(false)
						.build()
		);

		MockMultipartFile file = new MockMultipartFile(
				"file",
				"still.png",
				"image/png",
				new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}
		);
		mockMvc.perform(multipart("/api/v1/editor/projects/" + projectId + "/assets/images").file(file))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(assetId.toString()))
				.andExpect(jsonPath("$.type").value("IMAGE"))
				.andExpect(jsonPath("$.mimeType").value("image/png"));
	}

	@Test
	void replaceSegmentVisualReturnsImageSlot() throws Exception {
		UUID id = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		when(editorTimelineService.replaceSegmentVisual(eq(id), eq(segmentId), any())).thenReturn(
				sample(id, ProjectStatus.READY).toBuilder()
						.durationMillis(10_000L)
						.segments(List.of(EditorSegmentResponse.builder()
								.id(segmentId.toString())
								.label("A")
								.type(EditorSegmentType.IMAGE)
								.sourceStartMillis(0L)
								.sourceEndMillis(10_000L)
								.durationMillis(10_000)
								.assetId(assetId)
								.position(0)
								.build()))
						.build()
		);

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/segments/" + segmentId + "/visual")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "assetId": "%s" }
								""".formatted(assetId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].type").value("IMAGE"))
				.andExpect(jsonPath("$.segments[0].durationMillis").value(10000))
				.andExpect(jsonPath("$.segments[0].sourceStartMillis").value(0))
				.andExpect(jsonPath("$.segments[0].assetId").value(assetId.toString()));
	}

	@Test
	void startExportReturnsAccepted() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorRenderService.startRender(eq(id), any()))
				.thenReturn(sampleWithExport(id, ExportStatus.PREPARING, false));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/exports")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "fps": "ORIGINAL",
								  "resolution": "ORIGINAL",
								  "videoCodec": "H264",
								  "quality": "BALANCED",
								  "keepOriginalAudio": true
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.export.status").value("PREPARING"));
	}

	@Test
	void renderReturnsAccepted() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorRenderService.startRender(eq(id), org.mockito.ArgumentMatchers.nullable(UpdateEditorExportRequest.class)))
				.thenReturn(sampleWithExport(id, ExportStatus.PREPARING, false));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/render"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.export.status").value("PREPARING"));
	}

	@Test
	void exportRejectsWhenConcurrentLimitExceeded() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorRenderService.startRender(eq(id), any()))
				.thenThrow(new ConcurrentEditorLimitException("Maximum concurrent editor exports exceeded (1)"));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/exports")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "fps": "ORIGINAL",
								  "keepOriginalAudio": true
								}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("CONCURRENT_EDITOR_LIMIT_EXCEEDED"));
	}

	@Test
	void optionsReturnsV1ExportPresets() throws Exception {
		when(videoEditorService.options()).thenReturn(
				com.vhmedia.livedownloader.dto.response.EditorOptionsResponse.builder()
						.fps(List.of("ORIGINAL", "24", "25", "30", "50", "60"))
						.resolution(List.of("ORIGINAL", "1080p", "720p", "540p"))
						.codec(List.of("H264"))
						.quality(List.of("FAST", "BALANCED", "HIGH"))
						.segmentTypes(List.of("VIDEO", "IMAGE"))
						.playbackRates(List.of(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0))
						.imageSegmentsEnabled(false)
						.keepOriginalAudio(true)
						.build()
		);

		mockMvc.perform(get("/api/v1/editor/options"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fps[0]").value("ORIGINAL"))
				.andExpect(jsonPath("$.fps[4]").value("50"))
				.andExpect(jsonPath("$.fps[5]").value("60"))
				.andExpect(jsonPath("$.resolution[1]").value("1080p"))
				.andExpect(jsonPath("$.codec[0]").value("H264"))
				.andExpect(jsonPath("$.quality[1]").value("BALANCED"))
				.andExpect(jsonPath("$.segmentTypes[0]").value("VIDEO"))
				.andExpect(jsonPath("$.segmentTypes[1]").value("IMAGE"))
				.andExpect(jsonPath("$.playbackRates[0]").value(0.25))
				.andExpect(jsonPath("$.playbackRates[3]").value(1.0))
				.andExpect(jsonPath("$.playbackRates[6]").value(2.0))
				.andExpect(jsonPath("$.imageSegmentsEnabled").value(false))
				.andExpect(jsonPath("$.keepOriginalAudio").value(true));
	}

	@Test
	void updateExportReturnsPreviewGeometry() throws Exception {
		UUID id = UUID.randomUUID();
		EditorProjectResponse response = sample(id, ProjectStatus.READY).toBuilder()
				.export(EditorExportResponse.builder()
						.fps("30")
						.resolution("720p")
						.codec("H264")
						.outputWidth(1280)
						.outputHeight(720)
						.outputFps(30.0d)
						.outputVideoCodec("h264")
						.build())
				.build();
		when(videoEditorService.updateExportSettings(eq(id), any())).thenReturn(response);

		mockMvc.perform(put("/api/v1/editor/projects/" + id + "/export")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "fps": "30", "resolution": "720p", "codec": "H264" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.export.fps").value("30"))
				.andExpect(jsonPath("$.export.resolution").value("720p"))
				.andExpect(jsonPath("$.export.outputWidth").value(1280))
				.andExpect(jsonPath("$.export.outputVideoCodec").value("h264"));
	}

	@Test
	void cancelReturnsAccepted() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorRenderService.requestCancel(id)).thenReturn(sampleWithExport(id, ExportStatus.RENDERING, true));

		mockMvc.perform(post("/api/v1/editor/projects/" + id + "/cancel"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.export.status").value("RENDERING"))
				.andExpect(jsonPath("$.export.cancelRequested").value(true));
	}

	@Test
	void cancelExportByIdReturnsAccepted() throws Exception {
		UUID projectId = UUID.randomUUID();
		UUID exportId = UUID.randomUUID();
		when(videoEditorRenderService.requestCancelExport(exportId))
				.thenReturn(sampleWithExport(projectId, ExportStatus.RENDERING, true));

		mockMvc.perform(post("/api/v1/editor/exports/" + exportId + "/cancel"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.export.cancelRequested").value(true));
	}

	@Test
	void downloadExportFileWhenCompleted() throws Exception {
		UUID exportId = UUID.randomUUID();
		when(videoEditorService.getExportDownload(exportId)).thenReturn(
				new VideoEditorService.EditorFileDownload(new ByteArrayResource(new byte[]{1, 2}), "edit.mp4", 2)
		);

		mockMvc.perform(get("/api/v1/editor/exports/" + exportId + "/file"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"edit.mp4\""));
	}

	@Test
	void downloadExportBeforeCompleteReturnsConflict() throws Exception {
		UUID exportId = UUID.randomUUID();
		when(videoEditorService.getExportDownload(exportId))
				.thenThrow(new ExportNotReadyException("File download is only available for COMPLETED exports"));

		mockMvc.perform(get("/api/v1/editor/exports/" + exportId + "/file"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EXPORT_NOT_READY"));
	}

	@Test
	void downloadUnknownExportReturnsNotFound() throws Exception {
		UUID exportId = UUID.randomUUID();
		when(videoEditorService.getExportDownload(exportId))
				.thenThrow(new EditorExportNotFoundException("Editor export not found: " + exportId));

		mockMvc.perform(get("/api/v1/editor/exports/" + exportId + "/file"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
	}

	@Test
	void projectSourceSupportsByteRange() throws Exception {
		UUID id = UUID.randomUUID();
		when(editorAssetService.getPrimarySource(id)).thenReturn(
				new EditorAssetService.AssetFileDownload(
						new ByteArrayResource("0123456789".getBytes()),
						"source.mp4",
						10,
						"video/mp4"
				)
		);

		mockMvc.perform(get("/api/v1/editor/projects/" + id + "/source")
						.header(HttpHeaders.RANGE, "bytes=0-3"))
				.andExpect(status().isPartialContent())
				.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
				.andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/10"));
	}

	@Test
	void projectSourceWithoutRangeAdvertisesAcceptRanges() throws Exception {
		UUID id = UUID.randomUUID();
		when(editorAssetService.getPrimarySource(id)).thenReturn(
				new EditorAssetService.AssetFileDownload(
						new ByteArrayResource("0123456789".getBytes()),
						"source.mp4",
						10,
						"video/mp4"
				)
		);

		mockMvc.perform(get("/api/v1/editor/projects/" + id + "/source"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
				.andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 10));
	}

	@Test
	void assetContentSupportsByteRange() throws Exception {
		UUID assetId = UUID.randomUUID();
		when(editorAssetService.getContent(assetId)).thenReturn(
				new EditorAssetService.AssetFileDownload(
						new ByteArrayResource("abcdefghij".getBytes()),
						"clip.mp4",
						10,
						"video/mp4"
				)
		);

		mockMvc.perform(get("/api/v1/editor/assets/" + assetId + "/content")
						.header(HttpHeaders.RANGE, "bytes=2-5"))
				.andExpect(status().isPartialContent())
				.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
				.andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"));
	}

	@Test
	void listReturnsPage() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorService.list(isNull(), any())).thenReturn(new PageImpl<>(List.of(sample(id, ProjectStatus.READY))));

		mockMvc.perform(get("/api/v1/editor/projects").param("page", "0").param("size", "20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(id.toString()));
	}

	@Test
	void getUnknownReturnsNotFound() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorService.get(id)).thenThrow(new EditorProjectNotFoundException("missing"));

		mockMvc.perform(get("/api/v1/editor/projects/" + id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EDITOR_PROJECT_NOT_FOUND"));
	}

	@Test
	void downloadCompletedFile() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorService.getDownload(id)).thenReturn(
				new VideoEditorService.EditorFileDownload(new ByteArrayResource(new byte[]{1, 2}), "edit.mp4", 2)
		);

		mockMvc.perform(get("/api/v1/editor/projects/" + id + "/file"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"edit.mp4\""));
	}

	@Test
	void downloadBeforeCompleteReturnsConflict() throws Exception {
		UUID id = UUID.randomUUID();
		when(videoEditorService.getDownload(id))
				.thenThrow(new ExportNotReadyException("File download is only available after a COMPLETED export"));

		mockMvc.perform(get("/api/v1/editor/projects/" + id + "/file"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EXPORT_NOT_READY"));
	}

	@Test
	void deleteReturnsNoContent() throws Exception {
		UUID id = UUID.randomUUID();
		doNothing().when(videoEditorService).delete(id);

		mockMvc.perform(delete("/api/v1/editor/projects/" + id))
				.andExpect(status().isNoContent());
	}

	@Test
	void eventsRequiresExistingProject() throws Exception {
		UUID id = UUID.randomUUID();
		when(projectRepository.findById(id)).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/v1/editor/projects/" + id + "/events"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EDITOR_PROJECT_NOT_FOUND"));
	}

	@Test
	void exportEventsRequiresExistingExport() throws Exception {
		UUID exportId = UUID.randomUUID();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/v1/editor/exports/" + exportId + "/events"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
	}

	private static EditorProjectResponse sample(UUID id, ProjectStatus status) {
		return EditorProjectResponse.builder()
				.id(id)
				.status(status)
				.name("Cut 1")
				.title("Cut 1")
				.sourceType(VideoEditSourceType.UPLOAD)
				.hasVideo(true)
				.hasAudio(true)
				.videoCodec("h264")
				.audioCodec("aac")
				.width(1280)
				.height(720)
				.durationMillis(4000L)
				.sourceDurationMillis(4000L)
				.outputDurationMillis(4000L)
				.visualDurationMillis(4000L)
				.timelineVersion(0L)
				.sourceAssetId(UUID.randomUUID())
				.outputBaseName("edit_demo")
				.segments(List.of(EditorSegmentResponse.builder()
						.id(UUID.randomUUID().toString())
						.label("A")
						.type(EditorSegmentType.VIDEO)
						.sourceStartMillis(0L)
						.sourceEndMillis(4000L)
						.sourceDurationMillis(4000L)
						.durationMillis(4000)
						.playbackRate(1.0d)
						.visualDurationMillis(4000)
						.position(0)
						.build()))
				.createdAt(Instant.parse("2026-08-14T03:00:00Z"))
				.build();
	}

	private static EditorProjectResponse sampleWithExport(UUID id, ExportStatus exportStatus, boolean cancelRequested) {
		return sample(id, ProjectStatus.READY).toBuilder()
				.export(EditorExportResponse.builder()
						.id(UUID.randomUUID())
						.status(exportStatus)
						.fps("ORIGINAL")
						.resolution("ORIGINAL")
						.codec("H264")
						.cancelRequested(cancelRequested)
						.build())
				.build();
	}

	private static EditorProjectResponse reordered(UUID id) {
		return EditorProjectResponse.builder()
				.id(id)
				.status(ProjectStatus.READY)
				.sourceType(VideoEditSourceType.UPLOAD)
				.hasVideo(true)
				.hasAudio(true)
				.durationMillis(4000L)
				.segments(List.of(
						EditorSegmentResponse.builder()
								.id("1")
								.label("B")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(2000L)
								.sourceEndMillis(4000L)
								.durationMillis(2000)
								.position(0)
								.build(),
						EditorSegmentResponse.builder()
								.id("2")
								.label("A")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(0L)
								.sourceEndMillis(2000L)
								.durationMillis(2000)
								.position(1)
								.build()
				))
				.build();
	}

	private static EditorProjectResponse split(UUID id, UUID leftId) {
		return EditorProjectResponse.builder()
				.id(id)
				.status(ProjectStatus.READY)
				.sourceType(VideoEditSourceType.UPLOAD)
				.hasVideo(true)
				.hasAudio(true)
				.durationMillis(30000L)
				.segments(List.of(
						EditorSegmentResponse.builder()
								.id(leftId.toString())
								.label("A")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(0L)
								.sourceEndMillis(12000L)
								.durationMillis(12000)
								.position(0)
								.build(),
						EditorSegmentResponse.builder()
								.id(UUID.randomUUID().toString())
								.label("B")
								.type(EditorSegmentType.VIDEO)
								.sourceStartMillis(12000L)
								.sourceEndMillis(30000L)
								.durationMillis(18000)
								.position(1)
								.build()
				))
				.build();
	}
}
