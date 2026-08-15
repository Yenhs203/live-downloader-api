package com.vhmedia.livedownloader.exception;

public class InvalidPlaybackRateException extends ApiException {

	public InvalidPlaybackRateException(String message) {
		super(ErrorCode.INVALID_PLAYBACK_RATE, message);
	}
}
