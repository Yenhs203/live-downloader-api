package com.vhmedia.livedownloader.exception;

public class ConcurrentEditorLimitException extends ApiException {

	public ConcurrentEditorLimitException(String message) {
		super(ErrorCode.CONCURRENT_EDITOR_LIMIT_EXCEEDED, message);
	}
}
