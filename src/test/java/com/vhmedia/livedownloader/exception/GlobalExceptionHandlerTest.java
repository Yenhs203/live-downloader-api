package com.vhmedia.livedownloader.exception;

import com.vhmedia.livedownloader.dto.response.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

	private GlobalExceptionHandler handler;
	private MockHttpServletRequest request;
	private MockHttpServletResponse response;

	@BeforeEach
	void setUp() {
		handler = new GlobalExceptionHandler();
		request = new MockHttpServletRequest();
		request.setRequestURI("/api/v1/streams/probe");
		response = new MockHttpServletResponse();
	}

	@Test
	void returnsRfcStyleBodyWithoutStackTrace() {
		ResponseEntity<ErrorResponse> response = handler.handleApiException(
				new StreamProbeException("ffprobe stderr: https://cdn.example.com/x?token=abc"),
				request
		);

		ErrorResponse body = response.getBody();
		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(body).isNotNull();
		assertThat(body.getTimestamp()).isNotNull();
		assertThat(body.getStatus()).isEqualTo(422);
		assertThat(body.getCode()).isEqualTo("STREAM_PROBE_FAILED");
		assertThat(body.getMessage()).isEqualTo("Unable to read stream.");
		assertThat(body.getPath()).isEqualTo("/api/v1/streams/probe");
		assertThat(body.getMessage()).doesNotContain("token=");
		assertThat(body.getMessage()).doesNotContain("stderr");
	}

	@Test
	void redactsQueryParamsFromClientVisibleMessages() {
		request.setRequestURI("/api/v1/recordings");
		ResponseEntity<ErrorResponse> response = handler.handleApiException(
				new InvalidStreamUrlException("Bad URL https://cdn.example.com/live.flv?token=secret"),
				request
		);

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo("INVALID_STREAM_URL");
		assertThat(response.getBody().getMessage()).contains("?[REDACTED]");
		assertThat(response.getBody().getMessage()).doesNotContain("token=secret");
	}

	@Test
	void unexpectedErrorsReturnInternalCodeWithoutDetails() {
		ResponseEntity<ErrorResponse> entity = handler.handleUnexpectedException(
				new RuntimeException("secret boom"),
				request,
				response
		);

		assertThat(entity.getStatusCode().value()).isEqualTo(500);
		assertThat(entity.getBody()).isNotNull();
		assertThat(entity.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
		assertThat(entity.getBody().getMessage()).isEqualTo("Unexpected internal error.");
		assertThat(entity.getBody().getMessage()).doesNotContain("secret");
	}

	@Test
	void clientAbortWhileStreamingSourceIsIgnored() {
		request.setRequestURI("/api/v1/editor/projects/a8f83fd6-b8b8-463e-8a79-ca201e7a2e2b/source");
		IOException aborted = new IOException("An established connection was aborted by the software in your host machine");
		AsyncRequestNotUsableException disconnect = new AsyncRequestNotUsableException(
				"ServletOutputStream failed to write: " + aborted.getMessage(),
				aborted
		);

		assertThat(GlobalExceptionHandler.isClientDisconnected(disconnect)).isTrue();
		handler.handleUnusableResponse(disconnect, request);
		assertThat(handler.handleUnexpectedException(disconnect, request, response)).isNull();
	}

	@Test
	void doesNotWriteJsonErrorOntoCommittedVideoResponse() {
		request.setRequestURI("/api/v1/editor/projects/a8f83fd6-b8b8-463e-8a79-ca201e7a2e2b/source");
		HttpMessageNotWritableException notWritable = new HttpMessageNotWritableException(
				"No converter for [class com.vhmedia.livedownloader.dto.response.ErrorResponse] with preset Content-Type 'video/mp4'"
		);

		handler.handleUnusableResponse(notWritable, request);
		response.flushBuffer();
		assertThat(handler.handleUnexpectedException(new RuntimeException("late"), request, response)).isNull();
	}

	@Test
	void mapsListedDomainExceptionsToStableCodes() {
		assertThat(handler.handleApiException(new RecordingNotFoundException("missing"), request).getBody().getCode())
				.isEqualTo("RECORDING_NOT_FOUND");
		assertThat(handler.handleApiException(new InvalidRecordingStateException("bad state"), request).getBody().getCode())
				.isEqualTo("INVALID_RECORDING_STATE");
		assertThat(handler.handleApiException(new FfmpegStartException("start"), request).getBody().getCode())
				.isEqualTo("FFMPEG_START_FAILED");
		assertThat(handler.handleApiException(new FfmpegExecutionException("exec"), request).getBody().getCode())
				.isEqualTo("FFMPEG_EXECUTION_FAILED");
		assertThat(handler.handleApiException(new RemuxException("remux"), request).getBody().getCode())
				.isEqualTo("REMUX_FAILED");
		assertThat(handler.handleApiException(new StorageException("disk"), request).getBody().getCode())
				.isEqualTo("STORAGE_ERROR");
		assertThat(handler.handleApiException(new ConcurrentRecordingLimitException("limit"), request).getBody().getCode())
				.isEqualTo("CONCURRENT_LIMIT_EXCEEDED");
		assertThat(handler.handleApiException(new EditorProjectNotFoundException("missing"), request).getBody().getCode())
				.isEqualTo("EDITOR_PROJECT_NOT_FOUND");
		assertThat(handler.handleApiException(new EditorAssetNotFoundException("missing"), request).getBody().getCode())
				.isEqualTo("EDITOR_ASSET_NOT_FOUND");
		assertThat(handler.handleApiException(new EditorSegmentNotFoundException("missing cut"), request).getBody().getCode())
				.isEqualTo("EDITOR_SEGMENT_NOT_FOUND");
		assertThat(handler.handleApiException(new InvalidEditorFileException("bad file"), request).getBody().getCode())
				.isEqualTo("INVALID_EDITOR_FILE");
		assertThat(handler.handleApiException(new EditorSourceInvalidException("unreadable"), request).getBody().getCode())
				.isEqualTo("EDITOR_PROBE_FAILED");
		assertThat(handler.handleApiException(new InvalidSplitPositionException("bad split"), request).getBody().getCode())
				.isEqualTo("INVALID_SPLIT_POSITION");
		assertThat(handler.handleApiException(new InvalidSegmentBoundaryException("bad boundary"), request).getStatusCode().value())
				.isEqualTo(400);
		assertThat(handler.handleApiException(new InvalidSegmentBoundaryException("bad boundary"), request).getBody().getCode())
				.isEqualTo("INVALID_SEGMENT_BOUNDARY");
		assertThat(handler.handleApiException(new InvalidSegmentTrimException("bad trim"), request).getBody().getCode())
				.isEqualTo("INVALID_SEGMENT_TRIM");
		assertThat(handler.handleApiException(new InvalidOutputDurationException("zero output"), request).getBody().getCode())
				.isEqualTo("INVALID_OUTPUT_DURATION");
		assertThat(handler.handleApiException(new TimelineConflictException("stale"), request).getStatusCode().value())
				.isEqualTo(409);
		assertThat(handler.handleApiException(new TimelineConflictException("stale"), request).getBody().getCode())
				.isEqualTo("TIMELINE_CONFLICT");
		assertThat(handler.handleApiException(new TimelineConflictException("stale"), request).getBody().getMessage())
				.isEqualTo("stale");
		assertThat(handler.handleApiException(new SegmentTooShortException("too short"), request).getStatusCode().value())
				.isEqualTo(400);
		assertThat(handler.handleApiException(new SegmentTooShortException("too short"), request).getBody().getCode())
				.isEqualTo("SEGMENT_TOO_SHORT");
		assertThat(handler.handleApiException(new SegmentTooShortException("too short"), request).getBody().getMessage())
				.isEqualTo("too short");
		assertThat(handler.handleApiException(new SegmentsNotMergeableException("cannot merge"), request).getStatusCode().value())
				.isEqualTo(409);
		assertThat(handler.handleApiException(new SegmentsNotMergeableException("cannot merge"), request).getBody().getCode())
				.isEqualTo("SEGMENTS_NOT_MERGEABLE");
		assertThat(handler.handleApiException(new SegmentsNotMergeableException("cannot merge"), request).getBody().getMessage())
				.isEqualTo("cannot merge");
		assertThat(handler.handleApiException(new InvalidPlaybackRateException("bad rate"), request).getStatusCode().value())
				.isEqualTo(400);
		assertThat(handler.handleApiException(new InvalidPlaybackRateException("bad rate"), request).getBody().getCode())
				.isEqualTo("INVALID_PLAYBACK_RATE");
		assertThat(handler.handleApiException(new PlaybackRateNotSupportedForImageException(), request).getStatusCode().value())
				.isEqualTo(400);
		assertThat(handler.handleApiException(new PlaybackRateNotSupportedForImageException(), request).getBody().getCode())
				.isEqualTo("PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE");
		assertThat(handler.handleApiException(new OutputDurationExceedsAudioException("too long"), request).getStatusCode().value())
				.isEqualTo(400);
		assertThat(handler.handleApiException(new OutputDurationExceedsAudioException("too long"), request).getBody().getCode())
				.isEqualTo("OUTPUT_DURATION_EXCEEDS_AUDIO");
		assertThat(handler.handleApiException(new InvalidEditorSegmentsException("bad cuts"), request).getBody().getCode())
				.isEqualTo("INVALID_TIMELINE");
		assertThat(handler.handleApiException(new InvalidEditorExportException("bad fps"), request).getBody().getCode())
				.isEqualTo("INVALID_EDITOR_EXPORT");
		assertThat(handler.handleApiException(new ExportAlreadyRunningException("busy"), request).getBody().getCode())
				.isEqualTo("EXPORT_ALREADY_RUNNING");
		assertThat(handler.handleApiException(new EditorExportNotFoundException("missing"), request).getBody().getCode())
				.isEqualTo("EXPORT_NOT_FOUND");
		assertThat(handler.handleApiException(new ConcurrentEditorLimitException("limit"), request).getStatusCode().value())
				.isEqualTo(429);
		assertThat(handler.handleApiException(new ConcurrentEditorLimitException("limit"), request).getBody().getCode())
				.isEqualTo("CONCURRENT_EDITOR_LIMIT_EXCEEDED");
		assertThat(handler.handleApiException(new InvalidEditorStateException("bad project"), request).getStatusCode().value())
				.isEqualTo(409);
		assertThat(handler.handleApiException(new InvalidEditorStateException("bad project"), request).getBody().getCode())
				.isEqualTo("INVALID_EDITOR_STATE");
		assertThat(handler.handleApiException(new ExportNotReadyException("not ready"), request).getStatusCode().value())
				.isEqualTo(409);
		assertThat(handler.handleApiException(new ExportNotReadyException("not ready"), request).getBody().getCode())
				.isEqualTo("EXPORT_NOT_READY");
		assertThat(handler.handleApiException(new ExportAlreadyRunningException("busy"), request).getStatusCode().value())
				.isEqualTo(409);
	}

	@Test
	void mediaExecutableMissingHidesFilesystemPath() {
		request.setRequestURI("/api/v1/editor/projects");
		ResponseEntity<ErrorResponse> response = handler.handleApiException(
				new MediaExecutableMissingException("ffmpeg not found at C:\\tools\\ffmpeg.exe"),
				request
		);

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo("MEDIA_EXECUTABLE_MISSING");
		assertThat(response.getBody().getMessage()).isEqualTo("Media tools (ffmpeg/ffprobe) are not available.");
		assertThat(response.getBody().getMessage()).doesNotContain("C:\\");
		assertThat(response.getBody().getMessage()).doesNotContain("ffmpeg.exe");
	}

	@Test
	void exportFailedHidesFfmpegCommandAndPaths() {
		request.setRequestURI("/api/v1/editor/exports/x");
		ResponseEntity<ErrorResponse> response = handler.handleApiException(
				new EditorRenderException(
						"Editor render failed with exit code 1: ffmpeg -i C:\\secret\\source.mp4 -filter_complex [0:v]trim"
				),
				request
		);

		ErrorResponse body = response.getBody();
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(body).isNotNull();
		assertThat(body.getCode()).isEqualTo("EXPORT_FAILED");
		assertThat(body.getMessage()).isEqualTo("Editor export failed.");
		assertThat(body.getMessage()).doesNotContain("ffmpeg");
		assertThat(body.getMessage()).doesNotContain("C:\\");
		assertThat(body.getMessage()).doesNotContain("filter_complex");
	}

	@Test
	void editorStorageErrorHidesFilesystemPaths() {
		request.setRequestURI("/api/v1/editor/projects/x");
		ResponseEntity<ErrorResponse> response = handler.handleApiException(
				new EditorStorageException("Unable to delete editor file E:\\live-downloader\\recordings\\editor\\x\\source.mp4"),
				request
		);

		ErrorResponse body = response.getBody();
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(body).isNotNull();
		assertThat(body.getCode()).isEqualTo("EDITOR_STORAGE_ERROR");
		assertThat(body.getMessage()).isEqualTo("Editor storage operation failed.");
		assertThat(body.getMessage()).doesNotContain("E:\\");
		assertThat(body.getMessage()).doesNotContain("source.mp4");
	}

	@Test
	void editorMultipartTooLargeUsesEditorUploadCode() {
		request.setRequestURI("/api/v1/editor/projects");
		ResponseEntity<ErrorResponse> response = handler.handleMultipartException(
				new MaxUploadSizeExceededException(1),
				request
		);

		assertThat(response.getStatusCode().value()).isEqualTo(413);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo("EDITOR_UPLOAD_TOO_LARGE");
		assertThat(response.getBody().getMessage()).doesNotContain("Exception");
	}

	@Test
	void recordingMultipartTooLargeKeepsRecordingUploadCode() {
		request.setRequestURI("/api/v1/recordings");
		ResponseEntity<ErrorResponse> response = handler.handleMultipartException(
				new MaxUploadSizeExceededException(1),
				request
		);

		assertThat(response.getStatusCode().value()).isEqualTo(413);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo("UPLOAD_TOO_LARGE");
	}

	@Test
	void optimisticLockDoesNotLeakPersistenceDetails() {
		request.setRequestURI("/api/v1/editor/projects/x/segments/y/split");
		ResponseEntity<ErrorResponse> response = handler.handleOptimisticLock(
				new ObjectOptimisticLockingFailureException(
						"Row was updated or deleted by another transaction for entity [VideoProject]",
						new RuntimeException("Hibernate optimistic lock")
				),
				request
		);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo("TIMELINE_CONFLICT");
		assertThat(response.getBody().getMessage()).isEqualTo("Timeline was updated by another request. Reload and retry.");
		assertThat(response.getBody().getMessage()).doesNotContain("Hibernate");
		assertThat(response.getBody().getMessage()).doesNotContain("VideoProject");
		assertThat(response.getBody().getMessage()).doesNotContain("ffmpeg");
	}
}
