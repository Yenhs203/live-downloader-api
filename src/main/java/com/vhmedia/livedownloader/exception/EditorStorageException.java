package com.vhmedia.livedownloader.exception;

public class EditorStorageException extends StorageException {

	public EditorStorageException(String message) {
		super(ErrorCode.EDITOR_STORAGE_ERROR, message);
	}

	public EditorStorageException(String message, Throwable cause) {
		super(ErrorCode.EDITOR_STORAGE_ERROR, message, cause);
	}
}
