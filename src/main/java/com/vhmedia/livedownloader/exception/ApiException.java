package com.vhmedia.livedownloader.exception;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApiException(ErrorCode errorCode) {
		super(errorCode.getDefaultMessage());
		this.errorCode = errorCode;
	}

	public ApiException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public ApiException(ErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}
}
