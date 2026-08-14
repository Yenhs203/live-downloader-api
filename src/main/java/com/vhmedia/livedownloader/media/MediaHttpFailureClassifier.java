package com.vhmedia.livedownloader.media;

import java.io.IOException;
import java.util.Locale;

/**
 * Classifies ffmpeg/ffprobe failures from stderr or process-start errors for safer diagnostics.
 * Never returns or requires URL query tokens.
 */
public final class MediaHttpFailureClassifier {

	private MediaHttpFailureClassifier() {
	}

	public static MediaHttpFailureKind classify(String stderrOrDetail, Throwable cause) {
		if (isExecutableMissing(stderrOrDetail, cause)) {
			return MediaHttpFailureKind.EXECUTABLE_MISSING;
		}

		String text = normalize(stderrOrDetail);
		if (text.isEmpty() && cause != null) {
			text = normalize(cause.getMessage());
		}
		if (text.isEmpty()) {
			return MediaHttpFailureKind.UNKNOWN;
		}

		if (containsAny(text, "403", "forbidden", "access denied", "http error 403", "server returned 403")) {
			return MediaHttpFailureKind.HTTP_403;
		}
		if (containsAny(text, "404", "not found", "http error 404", "server returned 404")) {
			return MediaHttpFailureKind.HTTP_404;
		}
		if (containsAny(
				text,
				"timed out",
				"timeout",
				"connection timed out",
				"operation timed out",
				"i/o timeout"
		)) {
			return MediaHttpFailureKind.TIMEOUT;
		}
		if (containsAny(
				text,
				"name or service not known",
				"nodename nor servname",
				"temporary failure in name resolution",
				"could not resolve host",
				"failed to resolve",
				"getaddrinfo",
				"unknown host",
				"network is unreachable",
				"no route to host",
				"connection refused",
				"connection reset",
				"network unreachable",
				"failed to connect",
				"input/output error",
				"server returned 5"
		)) {
			return MediaHttpFailureKind.DNS_OR_NETWORK;
		}
		if (containsAny(
				text,
				"invalid data found",
				"invalid data found when processing input",
				"could not find codec",
				"unknown format",
				"protocol not found",
				"error opening input",
				"end of file",
				"moov atom not found",
				"invalid argument"
		)) {
			return MediaHttpFailureKind.INVALID_MEDIA;
		}
		return MediaHttpFailureKind.UNKNOWN;
	}

	public static MediaHttpFailureKind classifyStartFailure(IOException ex) {
		return classify(ex != null ? ex.getMessage() : null, ex);
	}

	private static boolean isExecutableMissing(String detail, Throwable cause) {
		String combined = normalize(detail);
		if (cause != null) {
			combined = (combined + " " + normalize(cause.getMessage())).trim();
			Throwable root = cause;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
				combined = (combined + " " + normalize(root.getMessage())).trim();
			}
		}
		return containsAny(
				combined,
				"cannot run program",
				"createprocess error=2",
				"no such file or directory",
				"the system cannot find the file specified",
				"error=2,"
		);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	private static boolean containsAny(String haystack, String... needles) {
		for (String needle : needles) {
			if (haystack.contains(needle)) {
				return true;
			}
		}
		return false;
	}
}
