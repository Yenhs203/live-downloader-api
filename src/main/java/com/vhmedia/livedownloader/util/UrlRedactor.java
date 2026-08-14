package com.vhmedia.livedownloader.util;

/**
 * Redacts sensitive query parameters from URLs before logging.
 */
public final class UrlRedactor {

	private UrlRedactor() {
	}

	/**
	 * Replaces the query string with {@code [REDACTED]}.
	 * <p>
	 * Example: {@code https://host/path/live.flv?token=abc&expire=123}
	 * becomes {@code https://host/path/live.flv?[REDACTED]}
	 */
	public static String redact(String url) {
		if (url == null || url.isBlank()) {
			return url;
		}

		int queryIndex = url.indexOf('?');
		if (queryIndex < 0) {
			return url;
		}

		return url.substring(0, queryIndex) + "?[REDACTED]";
	}

	/**
	 * Redacts query strings from any http(s) URLs embedded in free-form text (e.g. error messages).
	 */
	public static String redactInText(String text) {
		if (text == null || text.isBlank() || text.indexOf('?') < 0) {
			return text;
		}
		return text.replaceAll("(https?://[^\\s\"'<>]+?)\\?[^\\s\"'<>]*", "$1?[REDACTED]");
	}
}
