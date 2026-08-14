package com.vhmedia.livedownloader.exception;

public class InvalidStreamUrlException extends ApiException {

	public InvalidStreamUrlException(String message) {
		super(ErrorCode.INVALID_STREAM_URL, message);
	}
}
