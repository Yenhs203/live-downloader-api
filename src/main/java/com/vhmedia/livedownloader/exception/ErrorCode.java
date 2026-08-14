package com.vhmedia.livedownloader.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed."),
	INVALID_STREAM_URL(HttpStatus.BAD_REQUEST, "Invalid stream URL."),
	STREAM_PROBE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Unable to read stream."),
	STREAM_PROBE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Stream probe timed out."),
	MEDIA_EXECUTABLE_MISSING(HttpStatus.SERVICE_UNAVAILABLE, "Media tools (ffmpeg/ffprobe) are not available."),
	RECORDING_NOT_FOUND(HttpStatus.NOT_FOUND, "Recording not found."),
	INVALID_RECORDING_STATE(HttpStatus.CONFLICT, "Invalid recording state for this operation."),
	FFMPEG_START_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to start recording."),
	FFMPEG_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Recording process failed."),
	REMUX_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Remux failed."),
	STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Storage operation failed."),
	CONCURRENT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Maximum concurrent recordings exceeded."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error.");

	private final HttpStatus httpStatus;
	private final String defaultMessage;
}
