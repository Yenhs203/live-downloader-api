package com.vhmedia.livedownloader.media;

/**
 * Coarse failure categories for ffmpeg/ffprobe HTTP/media diagnostics (logging only).
 */
public enum MediaHttpFailureKind {
	HTTP_403,
	HTTP_404,
	TIMEOUT,
	DNS_OR_NETWORK,
	INVALID_MEDIA,
	EXECUTABLE_MISSING,
	UNKNOWN
}
