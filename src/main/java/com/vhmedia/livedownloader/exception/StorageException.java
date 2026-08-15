package com.vhmedia.livedownloader.exception;

public class StorageException extends ApiException {

	public StorageException(String message) {
		super(ErrorCode.STORAGE_ERROR, message);
	}

	public StorageException(String message, Throwable cause) {
		super(ErrorCode.STORAGE_ERROR, message, cause);
	}

	protected StorageException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}

	protected StorageException(ErrorCode errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}
}
