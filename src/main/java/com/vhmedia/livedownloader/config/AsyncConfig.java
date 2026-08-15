package com.vhmedia.livedownloader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

	@Bean
	RecordingTaskExecutor recordingTaskExecutor(MediaProperties mediaProperties) {
		int poolSize = Math.max(2, mediaProperties.getMaxConcurrentRecordings());
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("recording-");
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(0);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(mediaProperties.getStopTimeoutSeconds() + 5);
		executor.initialize();
		return new RecordingTaskExecutor(executor);
	}

	@Bean
	RemuxTaskExecutor remuxTaskExecutor(MediaProperties mediaProperties) {
		int poolSize = Math.max(2, Math.min(4, mediaProperties.getMaxConcurrentRecordings()));
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("remux-");
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		// Bounded queue so remux can wait while recordings occupy CPU/disk.
		executor.setQueueCapacity(Math.max(8, mediaProperties.getMaxConcurrentRecordings() * 2));
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		executor.initialize();
		return new RemuxTaskExecutor(executor);
	}

	@Bean
	EditorTaskExecutor editorTaskExecutor(EditorProperties editorProperties) {
		int poolSize = Math.max(1, editorProperties.getMaxConcurrentExports());
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("editor-");
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		// No queue: extra exports are rejected immediately (HTTP 429), same as recordings.
		executor.setQueueCapacity(0);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		executor.initialize();
		return new EditorTaskExecutor(executor);
	}
}
