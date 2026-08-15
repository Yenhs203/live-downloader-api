package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidEditorFileException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Still-image formats accepted as visual IMAGE assets in Phase 1B.
 * Content-Type is a first-pass whitelist only; acceptance is by file signature (magic bytes).
 */
public enum EditorImageFormat {
	JPEG("image/jpeg", "jpg"),
	PNG("image/png", "png"),
	WEBP("image/webp", "webp");

	private static final int HEADER_BYTES = 16;
	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
			"image/jpeg",
			"image/jpg",
			"image/png",
			"image/webp",
			"application/octet-stream"
	);

	private final String contentType;
	private final String extension;

	EditorImageFormat(String contentType, String extension) {
		this.contentType = contentType;
		this.extension = extension;
	}

	public String contentType() {
		return contentType;
	}

	public String extension() {
		return extension;
	}

	/**
	 * First-pass Content-Type whitelist. Uploads still must pass {@link #fromMagicBytes}.
	 */
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

	public static EditorImageFormat detect(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			throw new InvalidEditorFileException("Unsupported image type (allowed: JPEG, PNG, WEBP)");
		}
		try (InputStream in = Files.newInputStream(file)) {
			return fromMagicBytes(in.readNBytes(HEADER_BYTES));
		} catch (InvalidEditorFileException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new InvalidEditorFileException("Unable to read uploaded image");
		}
	}

	public static EditorImageFormat fromMagicBytes(byte[] header) {
		if (isJpeg(header)) {
			return JPEG;
		}
		if (isPng(header)) {
			return PNG;
		}
		if (isWebp(header)) {
			return WEBP;
		}
		throw new InvalidEditorFileException("Unsupported image type (allowed: JPEG, PNG, WEBP)");
	}

	/**
	 * Resolves a stored MIME type. Not used to accept uploads — those must pass {@link #fromMagicBytes}.
	 */
	public static EditorImageFormat fromStoredMime(String contentType) {
		String mime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
		if (mime.contains(";")) {
			mime = mime.substring(0, mime.indexOf(';')).trim();
		}
		for (EditorImageFormat format : values()) {
			if (format.contentType.equals(mime)) {
				return format;
			}
		}
		if ("image/jpg".equals(mime)) {
			return JPEG;
		}
		throw new InvalidEditorFileException("Unsupported image type (allowed: JPEG, PNG, WEBP)");
	}

	/** @deprecated use {@link #fromStoredMime(String)} or {@link #fromMagicBytes(byte[])} */
	public static EditorImageFormat fromUpload(String contentType, String originalFilename) {
		try {
			return fromStoredMime(contentType);
		} catch (InvalidEditorFileException ex) {
			if (originalFilename == null) {
				throw ex;
			}
			throw new InvalidEditorFileException("Unsupported image type (allowed: JPEG, PNG, WEBP)");
		}
	}

	private static boolean isJpeg(byte[] header) {
		return header != null
				&& header.length >= 3
				&& (header[0] & 0xFF) == 0xFF
				&& (header[1] & 0xFF) == 0xD8
				&& (header[2] & 0xFF) == 0xFF;
	}

	private static boolean isPng(byte[] header) {
		return header != null
				&& header.length >= 8
				&& (header[0] & 0xFF) == 0x89
				&& header[1] == 0x50
				&& header[2] == 0x4E
				&& header[3] == 0x47
				&& header[4] == 0x0D
				&& header[5] == 0x0A
				&& header[6] == 0x1A
				&& header[7] == 0x0A;
	}

	private static boolean isWebp(byte[] header) {
		return header != null
				&& header.length >= 12
				&& header[0] == 'R'
				&& header[1] == 'I'
				&& header[2] == 'F'
				&& header[3] == 'F'
				&& header[8] == 'W'
				&& header[9] == 'E'
				&& header[10] == 'B'
				&& header[11] == 'P';
	}
}
