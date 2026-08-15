package com.vhmedia.livedownloader.media;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime handle for an in-flight editor FFmpeg render. Not persisted.
 */
public final class RunningEditorRender {

	private final UUID projectId;
	private final UUID exportJobId;
	private final Process process;
	private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

	public RunningEditorRender(UUID projectId, Process process) {
		this(projectId, null, process);
	}

	public RunningEditorRender(UUID projectId, UUID exportJobId, Process process) {
		this.projectId = projectId;
		this.exportJobId = exportJobId;
		this.process = process;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public UUID getExportJobId() {
		return exportJobId;
	}

	public Process getProcess() {
		return process;
	}

	public boolean isCancelRequested() {
		return cancelRequested.get();
	}

	public boolean markCancelRequested() {
		return cancelRequested.compareAndSet(false, true);
	}
}
