package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.exception.ErrorCode;
import com.vhmedia.livedownloader.exception.UploadTooLargeException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Copies an {@link InputStream} to disk with a hard size cap. Never buffers the whole file in RAM.
 */
public final class CappedFileCopy {

	private static final int BUFFER_SIZE = 64 * 1024;

	private CappedFileCopy() {
	}

	public static long copy(InputStream in, Path dest, long maxBytes) throws IOException {
		return copy(in, dest, maxBytes, ErrorCode.UPLOAD_TOO_LARGE);
	}

	public static long copy(InputStream in, Path dest, long maxBytes, ErrorCode tooLargeCode) throws IOException {
		if (in == null || dest == null) {
			throw new IllegalArgumentException("stream and destination are required");
		}
		if (maxBytes < 1) {
			throw new IllegalArgumentException("maxBytes must be positive");
		}
		ErrorCode code = tooLargeCode != null ? tooLargeCode : ErrorCode.UPLOAD_TOO_LARGE;
		Path parent = dest.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		long total = 0L;
		try (OutputStream out = Files.newOutputStream(
				dest,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
		)) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				total += read;
				if (total > maxBytes) {
					throw new UploadTooLargeException(
							code,
							"Uploaded file exceeds the maximum allowed size (" + maxBytes + " bytes)"
					);
				}
				out.write(buffer, 0, read);
			}
		} catch (IOException | RuntimeException ex) {
			try {
				Files.deleteIfExists(dest);
			} catch (IOException ignored) {
				// best-effort
			}
			throw ex;
		}
		return total;
	}
}
