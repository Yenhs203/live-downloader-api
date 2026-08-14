package com.vhmedia.livedownloader.config;

import com.vhmedia.livedownloader.util.RecordingPathResolver;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;

/**
 * Reports recordings-volume free space for Actuator health.
 */
@Component("recordingsDisk")
public class RecordingsDiskHealthIndicator implements HealthIndicator {

	private final RecordingPathResolver pathResolver;
	private final MediaProperties mediaProperties;

	public RecordingsDiskHealthIndicator(RecordingPathResolver pathResolver, MediaProperties mediaProperties) {
		this.pathResolver = pathResolver;
		this.mediaProperties = mediaProperties;
	}

	@Override
	public Health health() {
		try {
			FileStore store = Files.getFileStore(pathResolver.getRecordingsRoot());
			long free = store.getUsableSpace();
			long total = store.getTotalSpace();
			long minFree = mediaProperties.getMinFreeDiskBytes();

			Health.Builder builder = free >= minFree ? Health.up() : Health.down();
			return builder
					.withDetail("path", pathResolver.getRecordingsRoot().toString())
					.withDetail("freeBytes", free)
					.withDetail("totalBytes", total)
					.withDetail("minFreeBytes", minFree)
					.build();
		} catch (IOException ex) {
			return Health.down(ex).withDetail("path", pathResolver.getRecordingsRoot().toString()).build();
		}
	}
}
