package com.vhmedia.livedownloader.media;

/**
 * SSE event names for {@code GET /api/v1/recordings/{id}/events}.
 */
public final class RecordingSseEvents {

	public static final String STARTED = "recording.started";
	public static final String PROGRESS = "recording.progress";
	public static final String STOPPING = "recording.stopping";
	public static final String REMUXING = "recording.remuxing";
	public static final String COMPLETED = "recording.completed";
	public static final String FAILED = "recording.failed";

	private RecordingSseEvents() {
	}

	public static String forStatus(com.vhmedia.livedownloader.enums.LiveJobStatus status) {
		if (status == null) {
			return PROGRESS;
		}
		return switch (status) {
			case RECORDING -> PROGRESS;
			case STOPPING -> STOPPING;
			case REMUXING -> REMUXING;
			case COMPLETED -> COMPLETED;
			case FAILED, INTERRUPTED -> FAILED;
			default -> PROGRESS;
		};
	}
}
