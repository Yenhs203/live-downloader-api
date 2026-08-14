package com.vhmedia.livedownloader.config;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated pool for FFmpeg recording tasks.
 * Intentionally does <strong>not</strong> implement {@link java.util.concurrent.Executor}
 * so Spring Boot can still auto-configure {@code applicationTaskExecutor}.
 */
public final class RecordingTaskExecutor {

	private final ThreadPoolTaskExecutor delegate;

	RecordingTaskExecutor(ThreadPoolTaskExecutor delegate) {
		this.delegate = delegate;
	}

	public void execute(Runnable task) {
		delegate.execute(task);
	}

	@PreDestroy
	void shutdown() {
		delegate.shutdown();
	}
}
