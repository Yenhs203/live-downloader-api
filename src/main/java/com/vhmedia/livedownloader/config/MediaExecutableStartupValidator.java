package com.vhmedia.livedownloader.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Optionally validates that ffmpeg/ffprobe are executable at startup.
 */
@Slf4j
@Component
public class MediaExecutableStartupValidator implements ApplicationRunner {

	private final MediaProperties mediaProperties;

	public MediaExecutableStartupValidator(MediaProperties mediaProperties) {
		this.mediaProperties = mediaProperties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!mediaProperties.isValidateExecutablesOnStartup()) {
			log.info("Skipping media executable validation (app.media.validate-executables-on-startup=false)");
			return;
		}

		verifyRunnable(mediaProperties.getFfmpegPath(), "ffmpeg");
		verifyRunnable(mediaProperties.getFfprobePath(), "ffprobe");
		log.info("Media executables validated ffmpeg={} ffprobe={}",
				mediaProperties.getFfmpegPath(), mediaProperties.getFfprobePath());
	}

	private static void verifyRunnable(String command, String label) {
		if (command == null || command.isBlank()) {
			throw new IllegalStateException(label + " path is blank");
		}

		Path asPath = Path.of(command);
		if (asPath.isAbsolute() && !Files.isRegularFile(asPath)) {
			throw new IllegalStateException(label + " executable not found at " + command);
		}

		ProcessBuilder builder = new ProcessBuilder(command, "-version");
		builder.redirectErrorStream(true);
		try {
			Process process = builder.start();
			boolean finished = process.waitFor(10, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException(label + " -version timed out (" + command + ")");
			}
			if (process.exitValue() != 0) {
				throw new IllegalStateException(label + " -version exited with code " + process.exitValue());
			}
			// Drain to avoid rare pipe fill on verbose banners.
			process.getInputStream().readAllBytes();
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to execute " + label + " at '" + command + "'", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(label + " validation interrupted", ex);
		}
	}
}
