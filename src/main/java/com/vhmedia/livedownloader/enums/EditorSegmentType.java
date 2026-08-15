package com.vhmedia.livedownloader.enums;

import com.vhmedia.livedownloader.exception.InvalidEditorSegmentsException;

/**
 * Visual clip kind. Array order on the project is the output visual order.
 * Audio is never a segment type — export audio stays {@code original[0..outputDuration]}.
 */
public enum EditorSegmentType {
	VIDEO,
	IMAGE;

	public static EditorSegmentType fromApi(String raw) {
		if (raw == null || raw.isBlank()) {
			return VIDEO;
		}
		String normalized = raw.trim();
		for (EditorSegmentType value : values()) {
			if (value.name().equalsIgnoreCase(normalized)) {
				return value;
			}
		}
		throw new InvalidEditorSegmentsException("Unsupported segment type: " + raw + " (allowed: VIDEO, IMAGE)");
	}
}
