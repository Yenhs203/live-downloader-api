package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.enums.ExportStatus;

import java.util.UUID;

public record EditorStatusChangedEvent(
		UUID projectId,
		UUID exportJobId,
		ExportStatus status,
		Long processedMillis,
		Long outputBytes
) {
	public EditorStatusChangedEvent(UUID projectId, UUID exportJobId, ExportStatus status) {
		this(projectId, exportJobId, status, null, null);
	}
}
