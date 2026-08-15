package com.vhmedia.livedownloader.editor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.vhmedia.livedownloader.exception.InvalidEditorExportException;

/**
 * V1 only supports H.264. Extra values can be added later without changing the project document shape.
 */
public enum EditorExportCodec {
	H264("H264", "libx264", "h264");

	private final String apiValue;
	private final String ffmpegEncoder;
	private final String outputCodecName;

	EditorExportCodec(String apiValue, String ffmpegEncoder, String outputCodecName) {
		this.apiValue = apiValue;
		this.ffmpegEncoder = ffmpegEncoder;
		this.outputCodecName = outputCodecName;
	}

	@JsonValue
	public String apiValue() {
		return apiValue;
	}

	public String ffmpegEncoder() {
		return ffmpegEncoder;
	}

	public String outputCodecName() {
		return outputCodecName;
	}

	@JsonCreator
	public static EditorExportCodec fromApi(String raw) {
		if (raw == null || raw.isBlank()) {
			return H264;
		}
		String normalized = raw.trim();
		if ("h.264".equalsIgnoreCase(normalized) || "avc".equalsIgnoreCase(normalized) || "avc1".equalsIgnoreCase(normalized)) {
			return H264;
		}
		for (EditorExportCodec value : values()) {
			if (value.apiValue.equalsIgnoreCase(normalized) || value.name().equalsIgnoreCase(normalized)) {
				return value;
			}
		}
		throw new InvalidEditorExportException("Unsupported export codec: " + raw + " (allowed: H264)");
	}
}
