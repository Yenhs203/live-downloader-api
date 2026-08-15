package com.vhmedia.livedownloader.editor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.vhmedia.livedownloader.exception.InvalidEditorExportException;

/**
 * Client-facing encode speed/quality. Mapped to x264 {@code -preset}/{@code -crf} in
 * {@link com.vhmedia.livedownloader.config.EditorProperties} — never sent as raw FFmpeg args.
 */
public enum EditorExportQuality {
	FAST("FAST"),
	BALANCED("BALANCED"),
	HIGH("HIGH");

	private final String apiValue;

	EditorExportQuality(String apiValue) {
		this.apiValue = apiValue;
	}

	@JsonValue
	public String apiValue() {
		return apiValue;
	}

	@JsonCreator
	public static EditorExportQuality fromApi(String raw) {
		if (raw == null || raw.isBlank()) {
			return BALANCED;
		}
		String normalized = raw.trim();
		for (EditorExportQuality value : values()) {
			if (value.apiValue.equalsIgnoreCase(normalized) || value.name().equalsIgnoreCase(normalized)) {
				return value;
			}
		}
		throw new InvalidEditorExportException(
				"Unsupported export quality: " + raw + " (allowed: FAST, BALANCED, HIGH)"
		);
	}
}
