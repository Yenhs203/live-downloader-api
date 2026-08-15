package com.vhmedia.livedownloader.enums;

/**
 * Editor export job lifecycle. Independent from recording job statuses.
 */
public enum ExportStatus {
	CREATED,
	PREPARING,
	RENDERING,
	FINALIZING,
	COMPLETED,
	FAILED,
	CANCELLED;

	public boolean isActive() {
		return this == CREATED || this == PREPARING || this == RENDERING || this == FINALIZING;
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
