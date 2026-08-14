package com.vhmedia.livedownloader.media;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory registry of active FFmpeg recording processes.
 * Database remains the persistent source of truth for job status.
 */
@Component
public class RecordingProcessRegistry {

	private final ConcurrentMap<UUID, RunningRecording> recordings = new ConcurrentHashMap<>();

	public boolean putIfAbsent(UUID jobId, RunningRecording recording) {
		return recordings.putIfAbsent(jobId, recording) == null;
	}

	public Optional<RunningRecording> get(UUID jobId) {
		return Optional.ofNullable(recordings.get(jobId));
	}

	public RunningRecording remove(UUID jobId) {
		return recordings.remove(jobId);
	}

	public boolean contains(UUID jobId) {
		return recordings.containsKey(jobId);
	}

	public int size() {
		return recordings.size();
	}

	public Collection<RunningRecording> snapshot() {
		return recordings.values();
	}
}
