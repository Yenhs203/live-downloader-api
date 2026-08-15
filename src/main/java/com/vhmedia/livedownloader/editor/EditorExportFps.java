package com.vhmedia.livedownloader.editor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.vhmedia.livedownloader.exception.InvalidEditorExportException;

/**
 * Export frame rate. Visual only — original audio timeline is never resampled to follow this.
 */
public enum EditorExportFps {
	ORIGINAL("ORIGINAL", null),
	FPS_24("24", 24.0d),
	FPS_25("25", 25.0d),
	FPS_30("30", 30.0d),
	FPS_50("50", 50.0d),
	FPS_60("60", 60.0d);

	private final String apiValue;
	private final Double fps;

	EditorExportFps(String apiValue, Double fps) {
		this.apiValue = apiValue;
		this.fps = fps;
	}

	@JsonValue
	public String apiValue() {
		return apiValue;
	}

	public Double fps() {
		return fps;
	}

	public boolean isOriginal() {
		return this == ORIGINAL;
	}

	@JsonCreator
	public static EditorExportFps fromApi(String raw) {
		if (raw == null || raw.isBlank()) {
			return ORIGINAL;
		}
		String normalized = raw.trim();
		for (EditorExportFps value : values()) {
			if (value.apiValue.equalsIgnoreCase(normalized) || value.name().equalsIgnoreCase(normalized)) {
				return value;
			}
		}
		throw new InvalidEditorExportException("Unsupported export fps: " + raw + " (allowed: ORIGINAL, 24, 25, 30, 50, 60)");
	}
}
