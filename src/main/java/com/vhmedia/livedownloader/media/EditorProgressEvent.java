package com.vhmedia.livedownloader.media;

import java.util.UUID;

public record EditorProgressEvent(
		UUID projectId,
		UUID exportJobId,
		RecordingProgress progress,
		Long totalMillis
) {
	public EditorProgressEvent(UUID projectId, RecordingProgress progress, Long totalMillis) {
		this(projectId, null, progress, totalMillis);
	}
}
