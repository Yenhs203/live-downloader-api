package com.vhmedia.livedownloader.exception;

public class UploadTooLargeException extends ApiException {

	public UploadTooLargeException(String message) {
		this(ErrorCode.UPLOAD_TOO_LARGE, message);
	}

	public UploadTooLargeException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
