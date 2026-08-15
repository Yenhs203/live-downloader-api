package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.enums.ExportStatus;

/**
 * SSE event names for editor export progress.
 * Primary stream: {@code GET /api/v1/editor/exports/{exportId}/events}.
 * {@code GET /api/v1/editor/projects/{id}/events} fans out the same events.
 */
public final class EditorSseEvents {

	public static final String STARTED = "editor.export.started";
	public static final String PROGRESS = "editor.export.progress";
	public static final String FINALIZING = "editor.export.finalizing";
	public static final String COMPLETED = "editor.export.completed";
	public static final String FAILED = "editor.export.failed";
	public static final String CANCELLED = "editor.export.cancelled";

	private EditorSseEvents() {
	}

	public static String forStatus(ExportStatus status) {
		if (status == null) {
			return STARTED;
		}
		return switch (status) {
			case CREATED, PREPARING -> STARTED;
			case RENDERING -> PROGRESS;
			case FINALIZING -> FINALIZING;
			case COMPLETED -> COMPLETED;
			case FAILED -> FAILED;
			case CANCELLED -> CANCELLED;
		};
	}
}
