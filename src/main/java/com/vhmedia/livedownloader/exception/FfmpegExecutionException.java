package com.vhmedia.livedownloader.exception;

public class FfmpegExecutionException extends ApiException {

	public FfmpegExecutionException(String message) {
		super(ErrorCode.FFMPEG_EXECUTION_FAILED, message);
	}

	public FfmpegExecutionException(String message, Throwable cause) {
		super(ErrorCode.FFMPEG_EXECUTION_FAILED, message, cause);
	}
}
