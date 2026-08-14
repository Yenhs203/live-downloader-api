package com.vhmedia.livedownloader.exception;

public class ConcurrentRecordingLimitException extends ApiException {

	public ConcurrentRecordingLimitException(String message) {
		super(ErrorCode.CONCURRENT_LIMIT_EXCEEDED, message);
	}
}
