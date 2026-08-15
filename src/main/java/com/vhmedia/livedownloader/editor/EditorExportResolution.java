package com.vhmedia.livedownloader.editor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.vhmedia.livedownloader.exception.InvalidEditorExportException;

/**
 * Export canvas. Orientation follows the source (portrait stays portrait).
 */
public enum EditorExportResolution {
	ORIGINAL("ORIGINAL", null),
	P1080("1080p", 1080),
	P720("720p", 720),
	P540("540p", 540);

	private final String apiValue;
	private final Integer shortSide;

	EditorExportResolution(String apiValue, Integer shortSide) {
		this.apiValue = apiValue;
		this.shortSide = shortSide;
	}

	@JsonValue
	public String apiValue() {
		return apiValue;
	}

	public Integer shortSide() {
		return shortSide;
	}

	public boolean isOriginal() {
		return this == ORIGINAL;
	}

	@JsonCreator
	public static EditorExportResolution fromApi(String raw) {
		if (raw == null || raw.isBlank()) {
			return ORIGINAL;
		}
		String normalized = raw.trim();
		for (EditorExportResolution value : values()) {
			if (value.apiValue.equalsIgnoreCase(normalized) || value.name().equalsIgnoreCase(normalized)) {
				return value;
			}
		}
		throw new InvalidEditorExportException(
				"Unsupported export resolution: " + raw + " (allowed: ORIGINAL, 1080p, 720p, 540p)"
		);
	}
}
