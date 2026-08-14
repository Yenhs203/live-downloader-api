package com.vhmedia.livedownloader.exception;

import com.vhmedia.livedownloader.dto.response.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

	private GlobalExceptionHandler handler;
	private MockHttpServletRequest request;

	@BeforeEach
	void setUp() {
		handler = new GlobalExceptionHandler();
		request = new MockHttpServletRequest();
		request.setRequestURI("/api/v1/streams/probe");
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
		ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
				new RuntimeException("secret boom"),
				request
		);

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
		assertThat(response.getBody().getMessage()).isEqualTo("Unexpected internal error.");
		assertThat(response.getBody().getMessage()).doesNotContain("secret");
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
	}
}
