package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;

import java.util.List;

/**
 * Appends browser-compatible HTTP options for ffmpeg/ffprobe ProcessBuilder argument lists.
 * <p>
 * Options are added as separate list entries (never via shell). Callers must still pass the
 * stream URL as its own argument and must not alter query encoding.
 */
public final class MediaHttpRequestArgs {

	private MediaHttpRequestArgs() {
	}

	/**
	 * Appends {@code -user_agent} and {@code -headers} when browser HTTP headers are enabled.
	 * Must be invoked before the input URL / {@code -i} argument.
	 */
	public static void appendBrowserCompatibleHttpArgs(List<String> command, MediaProperties mediaProperties) {
		if (command == null || mediaProperties == null || !mediaProperties.isHttpBrowserHeadersEnabled()) {
			return;
		}

		String userAgent = mediaProperties.getHttpUserAgent();
		String referer = mediaProperties.getHttpReferer();
		if (userAgent == null || userAgent.isBlank() || referer == null || referer.isBlank()) {
			return;
		}

		command.add("-user_agent");
		command.add(userAgent.trim());
		command.add("-headers");
		command.add(formatRefererHeader(referer.trim()));
	}

	/**
	 * FFmpeg/ffprobe expect each custom header line terminated by {@code \r\n}.
	 */
	static String formatRefererHeader(String referer) {
		String value = referer;
		if (value.endsWith("\r\n")) {
			value = value.substring(0, value.length() - 2);
		} else if (value.endsWith("\n")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.regionMatches(true, 0, "Referer:", 0, "Referer:".length())) {
			return value + "\r\n";
		}
		return "Referer: " + value + "\r\n";
	}
}
