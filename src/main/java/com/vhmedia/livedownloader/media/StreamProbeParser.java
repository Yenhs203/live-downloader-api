package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses ffprobe JSON ({@code -show_streams -show_format -of json}) into {@link StreamProbeResult}.
 */
@Component
public class StreamProbeParser {

	private final JsonMapper jsonMapper;

	public StreamProbeParser(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public StreamProbeResult parse(String json) {
		if (json == null || json.isBlank()) {
			throw new StreamProbeException("ffprobe returned empty output");
		}

		final JsonNode root;
		try {
			root = jsonMapper.readTree(json);
		} catch (Exception ex) {
			throw new StreamProbeException("Failed to parse ffprobe JSON output", ex);
		}

		JsonNode formatNode = root.path("format");
		String formatName = textOrNull(formatNode, "format_name");
		Long durationMillis = parseDurationMillis(textOrNull(formatNode, "duration"));

		boolean hasVideo = false;
		boolean hasAudio = false;
		String videoCodec = null;
		Integer width = null;
		Integer height = null;
		Double fps = null;
		String audioCodec = null;
		Integer audioSampleRate = null;
		Integer audioChannels = null;
		Long videoDurationMillis = null;
		Long audioDurationMillis = null;

		JsonNode streams = root.path("streams");
		if (streams.isArray()) {
			for (JsonNode stream : streams) {
				String codecType = textOrNull(stream, "codec_type");
				if ("video".equalsIgnoreCase(codecType) && !hasVideo) {
					hasVideo = true;
					videoCodec = textOrNull(stream, "codec_name");
					width = intOrNull(stream, "width");
					height = intOrNull(stream, "height");
					fps = parseFrameRate(textOrNull(stream, "avg_frame_rate"));
					if (fps == null) {
						fps = parseFrameRate(textOrNull(stream, "r_frame_rate"));
					}
					videoDurationMillis = durationFromStream(stream);
				} else if ("audio".equalsIgnoreCase(codecType) && !hasAudio) {
					hasAudio = true;
					audioCodec = textOrNull(stream, "codec_name");
					audioSampleRate = intOrNull(stream, "sample_rate");
					audioChannels = intOrNull(stream, "channels");
					audioDurationMillis = durationFromStream(stream);
				}
			}
		}

		return StreamProbeResult.builder()
				.hasVideo(hasVideo)
				.hasAudio(hasAudio)
				.formatName(formatName)
				.videoCodec(videoCodec)
				.width(width)
				.height(height)
				.fps(fps)
				.audioCodec(audioCodec)
				.audioSampleRate(audioSampleRate)
				.audioChannels(audioChannels)
				.durationMillis(durationMillis)
				.videoDurationMillis(videoDurationMillis)
				.audioDurationMillis(audioDurationMillis)
				.build();
	}

	static Long durationFromStream(JsonNode stream) {
		Long millis = parseDurationMillis(textOrNull(stream, "duration"));
		if (millis != null) {
			return millis;
		}
		JsonNode tags = stream.path("tags");
		millis = parseDurationMillis(textOrNull(tags, "DURATION"));
		if (millis != null) {
			return millis;
		}
		return parseDurationMillis(textOrNull(tags, "duration"));
	}

	static Long parseDurationMillis(String seconds) {
		if (seconds == null || seconds.isBlank() || "N/A".equalsIgnoreCase(seconds)) {
			return null;
		}
		String trimmed = seconds.trim();
		try {
			if (trimmed.indexOf(':') > 0) {
				String[] parts = trimmed.split(":");
				if (parts.length != 3) {
					return null;
				}
				double hours = Double.parseDouble(parts[0].trim());
				double minutes = Double.parseDouble(parts[1].trim());
				double secs = Double.parseDouble(parts[2].trim());
				double value = hours * 3600.0d + minutes * 60.0d + secs;
				if (value < 0.0d || !Double.isFinite(value)) {
					return null;
				}
				return Math.round(value * 1000.0d);
			}
			double value = Double.parseDouble(trimmed);
			if (value < 0.0d || !Double.isFinite(value)) {
				return null;
			}
			return Math.round(value * 1000.0d);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	static Double parseFrameRate(String rate) {
		if (rate == null || rate.isBlank() || "N/A".equalsIgnoreCase(rate) || "0/0".equals(rate)) {
			return null;
		}

		try {
			if (rate.contains("/")) {
				String[] parts = rate.split("/", 2);
				double numerator = Double.parseDouble(parts[0].trim());
				double denominator = Double.parseDouble(parts[1].trim());
				if (denominator == 0.0d) {
					return null;
				}
				return numerator / denominator;
			}
			return Double.parseDouble(rate.trim());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return text.isBlank() ? null : text;
	}

	private static Integer intOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isIntegralNumber()) {
			return value.intValue();
		}
		if (value.isString()) {
			String text = value.asString();
			if (text.isBlank()) {
				return null;
			}
			try {
				return Integer.parseInt(text.trim());
			} catch (NumberFormatException ex) {
				return null;
			}
		}
		if (value.isNumber()) {
			return value.intValue();
		}
		return null;
	}
}
