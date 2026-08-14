package com.vhmedia.livedownloader.support;

import java.util.concurrent.TimeUnit;

/**
 * Helpers for optional local FFmpeg-based tests. Never contacts external livestreams.
 */
public final class FfmpegTestSupport {

	private FfmpegTestSupport() {
	}

	public static boolean isFfmpegAvailable() {
		return commandSucceeds("ffmpeg", "-version");
	}

	public static boolean isFfprobeAvailable() {
		return commandSucceeds("ffprobe", "-version");
	}

	private static boolean commandSucceeds(String... command) {
		try {
			Process process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			boolean finished = process.waitFor(10, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0;
		} catch (Exception ex) {
			return false;
		}
	}
}
