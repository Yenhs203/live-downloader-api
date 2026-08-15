package com.vhmedia.livedownloader.util;

/**
 * How a completed recording MP4 was attached to an editor project.
 */
public enum RecordingImportMode {
	/**
	 * Same-volume hard link. Deleting the recording directory entry leaves the editor file intact.
	 */
	HARDLINK,
	/**
	 * Byte copy used when hard links are unavailable (cross-device, or the OS rejects the link).
	 */
	COPY
}
