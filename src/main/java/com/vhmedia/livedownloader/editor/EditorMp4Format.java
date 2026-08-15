package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidEditorFileException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Accepts ISO BMFF / MP4 by file signature ({@code ftyp} at offset 4).
 * Client extension and {@code Content-Type} are not trusted.
 */
public final class EditorMp4Format {

	private static final int HEADER_BYTES = 12;
	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
			"video/mp4",
			"application/mp4",
			"application/octet-stream"
	);

	private EditorMp4Format() {
	}

	public static boolean isAllowedContentType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return true;
		}
		String mime = contentType.trim().toLowerCase(Locale.ROOT);
		int semicolon = mime.indexOf(';');
		if (semicolon >= 0) {
			mime = mime.substring(0, semicolon).trim();
		}
		return ALLOWED_CONTENT_TYPES.contains(mime);
	}

	public static void assertMp4(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			throw new InvalidEditorFileException("Only MP4 files are accepted");
		}
		try (InputStream in = Files.newInputStream(file)) {
			assertMp4(in.readNBytes(HEADER_BYTES));
		} catch (InvalidEditorFileException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new InvalidEditorFileException("Only MP4 files are accepted");
		}
	}

	static void assertMp4(byte[] header) {
		if (header != null
				&& header.length >= 8
				&& header[4] == 'f'
				&& header[5] == 't'
				&& header[6] == 'y'
				&& header[7] == 'p') {
			return;
		}
		throw new InvalidEditorFileException("Only MP4 files are accepted");
	}
}
