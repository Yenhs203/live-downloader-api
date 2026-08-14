package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.enums.LiveJobStatus;

import java.util.UUID;

/**
 * Domain event published when a job transitions to a UI-visible status.
 */
public record JobStatusChangedEvent(
		UUID jobId,
		LiveJobStatus status,
		Long downloadedBytes,
		Long durationMillis
) {
	public JobStatusChangedEvent(UUID jobId, LiveJobStatus status) {
		this(jobId, status, null, null);
	}
}
