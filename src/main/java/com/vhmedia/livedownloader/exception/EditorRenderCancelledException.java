package com.vhmedia.livedownloader.exception;

/**
 * Render stopped because the user cancelled. Not an HTTP API error by itself.
 */
public class EditorRenderCancelledException extends RuntimeException {

	public EditorRenderCancelledException() {
		super("Editor render cancelled");
	}
}
