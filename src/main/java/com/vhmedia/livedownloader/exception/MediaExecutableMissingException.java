package com.vhmedia.livedownloader.exception;

/**
 * Raised when ffmpeg/ffprobe cannot be started because the executable is missing from PATH
 * or {@code FFMPEG_PATH}/{@code FFPROBE_PATH} is invalid.
 */
public class MediaExecutableMissingException extends ApiException {

	public MediaExecutableMissingException(String message) {
		super(ErrorCode.MEDIA_EXECUTABLE_MISSING, message);
	}

	public MediaExecutableMissingException(String message, Throwable cause) {
		super(ErrorCode.MEDIA_EXECUTABLE_MISSING, message, cause);
	}
}
