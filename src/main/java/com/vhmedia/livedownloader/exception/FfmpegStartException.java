package com.vhmedia.livedownloader.exception;

public class FfmpegStartException extends ApiException {

	public FfmpegStartException(String message) {
		super(ErrorCode.FFMPEG_START_FAILED, message);
	}

	public FfmpegStartException(String message, Throwable cause) {
		super(ErrorCode.FFMPEG_START_FAILED, message, cause);
	}
}
