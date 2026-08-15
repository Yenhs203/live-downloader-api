package com.vhmedia.livedownloader.config;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated pool for visual-reorder renders so editor work never shares the
 * recording or remux pools.
 */
public final class EditorTaskExecutor {

	private final ThreadPoolTaskExecutor delegate;

	EditorTaskExecutor(ThreadPoolTaskExecutor delegate) {
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
