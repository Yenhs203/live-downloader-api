package com.vhmedia.livedownloader.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Server-side recording/editor base-name generator. Never derived from user input paths.
 */
public final class OutputBaseNameGenerator {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	private OutputBaseNameGenerator() {
	}

	/**
	 * Example: {@code live_20260811_140530_a1b2c3d4}
	 */
	public static String generate() {
		return prefix("live");
	}

	/**
	 * Example: {@code edit_20260814_101530_a1b2c3d4}
	 */
	public static String generateEditor() {
		return prefix("edit");
	}

	private static String prefix(String kind) {
		String timestamp = LocalDateTime.now().format(FORMATTER);
		String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return kind + "_" + timestamp + "_" + shortUuid;
	}
}
