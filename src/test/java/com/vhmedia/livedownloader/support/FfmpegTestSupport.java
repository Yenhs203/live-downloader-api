package com.vhmedia.livedownloader.support;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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

	public static boolean isMediaToolsAvailable() {
		return isFfmpegAvailable() && isFfprobeAvailable();
	}

	public static void run(List<String> command) throws Exception {
		run(command, 60);
	}

	public static void run(List<String> command, long timeoutSeconds) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		ByteArrayOutputStream combined = new ByteArrayOutputStream();
		try (InputStream in = process.getInputStream()) {
			in.transferTo(combined);
		}
		boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalStateException("Command timed out: " + command.getFirst());
		}
		if (process.exitValue() != 0) {
			throw new IllegalStateException(
					"Command failed exit=" + process.exitValue()
							+ " cmd=" + command.getFirst()
							+ " output=" + tail(combined.toString(StandardCharsets.UTF_8))
			);
		}
	}

	public static byte[] runAndCaptureStdout(List<String> command) throws Exception {
		Process process = new ProcessBuilder(command).start();
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		try (InputStream in = process.getInputStream()) {
			in.transferTo(stdout);
		}
		try (InputStream err = process.getErrorStream()) {
			err.transferTo(stderr);
		}
		boolean finished = process.waitFor(60, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalStateException("Command timed out: " + command.getFirst());
		}
		if (process.exitValue() != 0) {
			throw new IllegalStateException(
					"Command failed exit=" + process.exitValue()
							+ " cmd=" + command.getFirst()
							+ " stderr=" + tail(stderr.toString(StandardCharsets.UTF_8))
			);
		}
		return stdout.toByteArray();
	}

	public static String quote(Path path) {
		return path.toAbsolutePath().toString();
	}

	private static String tail(String text) {
		if (text == null) {
			return "";
		}
		String trimmed = text.trim();
		if (trimmed.length() <= 2000) {
			return trimmed;
		}
		return trimmed.substring(trimmed.length() - 2000);
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
