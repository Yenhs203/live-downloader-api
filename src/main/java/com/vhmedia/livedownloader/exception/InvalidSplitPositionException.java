package com.vhmedia.livedownloader.exception;

public class InvalidSplitPositionException extends InvalidEditorSegmentsException {

	public InvalidSplitPositionException(String message) {
		super(ErrorCode.INVALID_SPLIT_POSITION, message);
	}
}
