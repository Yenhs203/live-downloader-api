package com.vhmedia.livedownloader.media;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime handle for an in-flight FFmpeg recording. Not persisted.
 */
public final class RunningRecording {

	private final UUID jobId;
	private final Process process;
	private final Instant startedAt;
	private final Path outputPath;
	private final AtomicBoolean stopRequested = new AtomicBoolean(false);
	private final AtomicReference<RecordingProgress> lastProgress = new AtomicReference<>();

	public RunningRecording(UUID jobId, Process process, Path outputPath) {
		this.jobId = jobId;
		this.process = process;
		this.outputPath = outputPath;
		this.startedAt = Instant.now();
	}

	public UUID getJobId() {
		return jobId;
	}

	public Process getProcess() {
		return process;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Path getOutputPath() {
		return outputPath;
	}

	public boolean isStopRequested() {
		return stopRequested.get();
	}

	public boolean markStopRequested() {
		return stopRequested.compareAndSet(false, true);
	}

	public void updateProgress(RecordingProgress progress) {
		lastProgress.set(progress);
	}

	public RecordingProgress getLastProgress() {
		return lastProgress.get();
	}
}
