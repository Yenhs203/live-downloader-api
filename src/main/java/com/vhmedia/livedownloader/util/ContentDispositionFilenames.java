package com.vhmedia.livedownloader.util;

/**
 * Builds {@code Content-Disposition} values with a sanitized ASCII filename.
 * Strips quotes, CR/LF, and path segments so the header cannot be injected.
 */
public final class ContentDispositionFilenames {

	private ContentDispositionFilenames() {
	}

	public static String attachment(String filename) {
		return "attachment; filename=\"" + sanitize(filename, "download.mp4") + "\"";
	}

	public static String inline(String filename) {
		return "inline; filename=\"" + sanitize(filename, "video.mp4") + "\"";
	}

	static String sanitize(String filename, String fallback) {
		String name = SafeOriginalFilename.displayName(filename);
		if (name == null) {
			return fallback;
		}
		StringBuilder safe = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			if (ch == '"' || ch == '\r' || ch == '\n' || ch == '\\' || ch < 32) {
				continue;
			}
			safe.append(ch);
		}
		if (safe.isEmpty()) {
			return fallback;
		}
		return safe.toString();
	}
}
