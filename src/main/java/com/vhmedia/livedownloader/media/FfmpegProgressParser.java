package com.vhmedia.livedownloader.media;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses FFmpeg {@code -progress pipe:1} output.
 * Progress is emitted as {@code key=value} lines, ending with {@code progress=continue|end}.
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
	public static java.util.Optional<RecordingProgress> acceptLine(
			Map<String, String> accumulatingBlock,
			String line
	) {
		if (line == null || line.isBlank()) {
			return java.util.Optional.empty();
		}

		int separator = line.indexOf('=');
		if (separator <= 0) {
			return java.util.Optional.empty();
		}

		String key = line.substring(0, separator).trim();
		String value = line.substring(separator + 1).trim();
		accumulatingBlock.put(key, value);

		if ("progress".equals(key)) {
			RecordingProgress parsed = parseBlock(new LinkedHashMap<>(accumulatingBlock));
			accumulatingBlock.clear();
			return java.util.Optional.of(parsed);
		}

		return java.util.Optional.empty();
	}

	static Long parseOutTimeMs(Map<String, String> properties) {
		Long outTimeUs = parseLong(properties.get("out_time_us"));
		if (outTimeUs != null) {
			return outTimeUs / 1000L;
		}
		return parseLong(properties.get("out_time_ms"));
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
