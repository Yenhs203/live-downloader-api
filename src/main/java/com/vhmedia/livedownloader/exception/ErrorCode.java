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

	EDITOR_PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "Editor project not found."),
	EDITOR_ASSET_NOT_FOUND(HttpStatus.NOT_FOUND, "Editor asset not found."),
	EDITOR_SEGMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Editor segment not found."),
	INVALID_EDITOR_FILE(HttpStatus.BAD_REQUEST, "Invalid editor file."),
	EDITOR_UPLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded editor file exceeds the maximum allowed size."),
	EDITOR_PROBE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Unable to read editor source video."),
	INVALID_SPLIT_POSITION(HttpStatus.BAD_REQUEST, "Invalid split position."),
	INVALID_SEGMENT_BOUNDARY(HttpStatus.BAD_REQUEST, "Invalid shared-cut boundary."),
	SEGMENT_TOO_SHORT(HttpStatus.BAD_REQUEST, "This clip would be shorter than the minimum duration."),
	INVALID_SEGMENT_TRIM(HttpStatus.BAD_REQUEST, "Invalid segment trim."),
	SEGMENTS_NOT_MERGEABLE(HttpStatus.CONFLICT, "These clips cannot be merged."),
	INVALID_PLAYBACK_RATE(HttpStatus.BAD_REQUEST, "Unsupported playback rate."),
	PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE(HttpStatus.BAD_REQUEST, "IMAGE clips do not support playbackRate."),
	INVALID_OUTPUT_DURATION(HttpStatus.BAD_REQUEST, "Output duration must be greater than 0."),
	OUTPUT_DURATION_EXCEEDS_AUDIO(HttpStatus.BAD_REQUEST, "Output duration cannot exceed the original audio duration."),
	INVALID_TIMELINE(HttpStatus.BAD_REQUEST, "Invalid editor timeline."),
	TIMELINE_CONFLICT(HttpStatus.CONFLICT, "Timeline was updated by another request. Reload and retry."),
	INVALID_EDITOR_STATE(HttpStatus.CONFLICT, "Invalid editor project state for this operation."),
	INVALID_EDITOR_EXPORT(HttpStatus.BAD_REQUEST, "Invalid editor export settings."),
	EXPORT_ALREADY_RUNNING(HttpStatus.CONFLICT, "An export job is already in progress."),
	EXPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "Editor export not found."),
	EXPORT_NOT_READY(HttpStatus.CONFLICT, "Export is not ready for this operation."),
	EXPORT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Editor export failed."),
	EDITOR_STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Editor storage operation failed."),
	CONCURRENT_EDITOR_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Maximum concurrent editor exports exceeded."),
	UPLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the maximum allowed size."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error.");

	private final HttpStatus httpStatus;
	private final String defaultMessage;
}
