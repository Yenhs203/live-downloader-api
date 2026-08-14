package com.vhmedia.livedownloader.config;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated pool for remux work so remux is never scheduled onto the saturated
 * recording pool ({@code queueCapacity=0}).
 */
public final class RemuxTaskExecutor {

	private final ThreadPoolTaskExecutor delegate;

	RemuxTaskExecutor(ThreadPoolTaskExecutor delegate) {
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
