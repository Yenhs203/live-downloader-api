package com.vhmedia.livedownloader.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses FFmpeg {@code -progress pipe:1} output.
 * Progress is emitted as {@code key=value} lines, ending with {@code progress=continue|end}.
 * <p>
 * Time is taken from {@code out_time_us} (microseconds) when present, otherwise
 * {@code out_time} ({@code HH:MM:SS.micro}), otherwise {@code out_time_ms}.
 * {@code N/A} and malformed numbers are ignored.
 */
public final class FfmpegProgressParser {

	private FfmpegProgressParser() {
	}

	public static RecordingProgress parseBlock(Map<String, String> properties) {
		if (properties == null || properties.isEmpty()) {
			return RecordingProgress.builder().build();
		}

		return RecordingProgress.builder()
				.outTimeMs(parseOutTimeMs(properties))
				.totalSize(parseLong(properties.get("total_size")))
				.speed(blankToNull(properties.get("speed")))
				.fps(parseDouble(properties.get("fps")))
				.bitrate(blankToNull(properties.get("bitrate")))
				.progress(blankToNull(properties.get("progress")))
				.build();
	}

	/**
	 * Applies one {@code key=value} line into the accumulating block.
	 *
	 * @return completed {@link RecordingProgress} when a {@code progress=} line is seen; otherwise empty
	 */
	public static Optional<RecordingProgress> acceptLine(
			Map<String, String> accumulatingBlock,
			String line
	) {
		if (line == null || line.isBlank()) {
			return Optional.empty();
		}

		int separator = line.indexOf('=');
		if (separator <= 0) {
			return Optional.empty();
		}

		String key = line.substring(0, separator).trim();
		String value = line.substring(separator + 1).trim();
		accumulatingBlock.put(key, value);

		if ("progress".equals(key)) {
			RecordingProgress parsed = parseBlock(new LinkedHashMap<>(accumulatingBlock));
			accumulatingBlock.clear();
			return Optional.of(parsed);
		}

		return Optional.empty();
	}

	static Long parseOutTimeMs(Map<String, String> properties) {
		if (properties == null || properties.isEmpty()) {
			return null;
		}
		Long outTimeUs = parseLong(properties.get("out_time_us"));
		if (outTimeUs != null) {
			return outTimeUs / 1000L;
		}
		Long fromClock = parseClock(properties.get("out_time"));
		if (fromClock != null) {
			return fromClock;
		}
		Long outTimeMs = parseLong(properties.get("out_time_ms"));
		if (outTimeMs == null) {
			return null;
		}
		// Recent FFmpeg documents out_time_ms as a deprecated alias of out_time_us
		// (microseconds). Values that cannot be wall-clock milliseconds are treated as us.
		if (outTimeMs >= 24L * 60L * 60L * 1000L) {
			return outTimeMs / 1000L;
		}
		return outTimeMs;
	}

	/**
	 * Parses FFmpeg {@code speed} values such as {@code 1.7x} into a numeric factor.
	 */
	public static Double parseSpeed(String value) {
		String trimmed = blankToNull(value);
		if (trimmed == null) {
			return null;
		}
		if (trimmed.endsWith("x") || trimmed.endsWith("X")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return parseDouble(trimmed);
	}

	static Long parseClock(String value) {
		String trimmed = blankToNull(value);
		if (trimmed == null) {
			return null;
		}
		String[] parts = trimmed.split(":");
		if (parts.length < 2) {
			return null;
		}
		try {
			double seconds = 0.0d;
			for (String part : parts) {
				seconds = seconds * 60.0d + Double.parseDouble(part);
			}
			return Math.round(seconds * 1000.0d);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Long parseLong(String value) {
		if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Double parseDouble(String value) {
		if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
			return null;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
			return null;
		}
		return value;
	}
}
