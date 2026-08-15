package com.vhmedia.livedownloader.enums;

/**
 * Who owns the editor source bytes on disk.
 * <ul>
 *   <li>{@link #UPLOAD} — editor owns the uploaded MP4 and may delete it.</li>
 *   <li>{@link #RECORDING_COPY} — editor owns an independent copy; the recording original is untouched.</li>
 *   <li>{@link #RECORDING_HARDLINK} — editor owns only its directory entry. Unlink on delete;
 *       never delete the recording original path.</li>
 * </ul>
 */
public enum EditorSourceStorageMode {
	UPLOAD,
	RECORDING_COPY,
	RECORDING_HARDLINK
}
