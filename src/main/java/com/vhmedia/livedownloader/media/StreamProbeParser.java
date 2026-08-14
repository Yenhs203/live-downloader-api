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

		boolean hasVideo = false;
		boolean hasAudio = false;
		String videoCodec = null;
		Integer width = null;
		Integer height = null;
		Double fps = null;
		String audioCodec = null;
		Integer audioSampleRate = null;
		Integer audioChannels = null;

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
				} else if ("audio".equalsIgnoreCase(codecType) && !hasAudio) {
					hasAudio = true;
					audioCodec = textOrNull(stream, "codec_name");
					audioSampleRate = intOrNull(stream, "sample_rate");
					audioChannels = intOrNull(stream, "channels");
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
				.build();
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
