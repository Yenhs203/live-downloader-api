package com.vhmedia.livedownloader.exception;

public class RemuxException extends ApiException {

	public RemuxException(String message) {
		super(ErrorCode.REMUX_FAILED, message);
	}

	public RemuxException(String message, Throwable cause) {
		super(ErrorCode.REMUX_FAILED, message, cause);
	}
}
