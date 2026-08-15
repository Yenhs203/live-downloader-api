package com.vhmedia.livedownloader.exception;

public class EditorAssetNotFoundException extends ApiException {

	public EditorAssetNotFoundException(String message) {
		super(ErrorCode.EDITOR_ASSET_NOT_FOUND, message);
	}
}
