package com.vhmedia.livedownloader.util;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Sanitizes client-supplied filenames for display metadata only. Never used as a storage path.
 */
public final class SafeOriginalFilename {

	private static final int MAX_LENGTH = 255;

	private SafeOriginalFilename() {
	}

	public static String displayName(String original) {
		if (original == null || original.isBlank()) {
			return null;
		}
		if (original.contains("..")) {
			return null;
		}
		String normalized = original.replace('\\', '/').trim();
		int slash = normalized.lastIndexOf('/');
		if (slash >= 0) {
			normalized = normalized.substring(slash + 1);
		}
		if (normalized.isBlank() || normalized.contains("..") || normalized.equals(".") || normalized.equals("..")) {
			return null;
		}
		if (normalized.length() > MAX_LENGTH) {
			normalized = normalized.substring(0, MAX_LENGTH);
		}
		return normalized;
	}

	public static boolean looksLikeMp4(String original) {
		String name = displayName(original);
		return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4");
	}

	public static String fileNameOf(Path path) {
		if (path == null || path.getFileName() == null) {
			return "file.bin";
		}
		return path.getFileName().toString();
	}
}
