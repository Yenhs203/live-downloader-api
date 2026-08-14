package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.exception.StorageException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves recording file paths and keeps them inside the configured recordings directory.
 */
@Component
public class RecordingPathResolver {

	private final Path recordingsRoot;
	private final long minFreeDiskBytes;

	public RecordingPathResolver(MediaProperties mediaProperties) {
		try {
			this.recordingsRoot = Paths.get(mediaProperties.getRecordingsDirectory()).toAbsolutePath().normalize();
			this.minFreeDiskBytes = mediaProperties.getMinFreeDiskBytes();
			Files.createDirectories(this.recordingsRoot);
			assertWritable();
			assertMinimumFreeSpace();
		} catch (IOException ex) {
			throw new StorageException("Unable to initialize recordings directory", ex);
		}
	}

	public Path getRecordingsRoot() {
		return recordingsRoot;
	}

	public Path resolveTsPath(String outputBaseName) {
		return resolveInsideRoot(outputBaseName + ".ts");
	}

	public Path resolveMp4Path(String outputBaseName) {
		return resolveInsideRoot(outputBaseName + ".mp4");
	}

	public Path resolveInsideRoot(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			throw new StorageException("fileName must not be blank");
		}
		if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
			throw new StorageException("Invalid recording file name");
		}

		Path resolved = recordingsRoot.resolve(fileName).toAbsolutePath().normalize();
		if (!resolved.startsWith(recordingsRoot)) {
			throw new StorageException("Resolved path escapes recordings directory");
		}
		return resolved;
	}

	public Path toPath(String storedPath) {
		if (storedPath == null || storedPath.isBlank()) {
			throw new StorageException("stored path must not be blank");
		}
		Path path = Paths.get(storedPath).toAbsolutePath().normalize();
		if (!path.startsWith(recordingsRoot)) {
			throw new StorageException("Stored path escapes recordings directory");
		}
		return path;
	}

	/**
	 * Best-effort check before accepting a new recording. Does not reserve space.
	 */
	public void assertWritableAndHasFreeSpace() {
		try {
			assertWritable();
			assertMinimumFreeSpace();
		} catch (IOException ex) {
			throw new StorageException("Recordings directory is not usable", ex);
		}
	}

	private void assertWritable() throws IOException {
		if (!Files.isDirectory(recordingsRoot)) {
			throw new StorageException("Recordings path is not a directory: " + recordingsRoot);
		}
		if (!Files.isWritable(recordingsRoot)) {
			throw new StorageException("Recordings directory is not writable: " + recordingsRoot);
		}
		Path probe = recordingsRoot.resolve(".write-probe-" + ProcessHandle.current().pid());
		try {
			Files.writeString(probe, "ok");
		} finally {
			Files.deleteIfExists(probe);
		}
	}

	private void assertMinimumFreeSpace() throws IOException {
		if (minFreeDiskBytes <= 0) {
			return;
		}
		FileStore store = Files.getFileStore(recordingsRoot);
		long free = store.getUsableSpace();
		if (free < minFreeDiskBytes) {
			throw new StorageException(
					"Insufficient disk space under " + recordingsRoot
							+ " (free=" + free + " bytes, required=" + minFreeDiskBytes + ")"
			);
		}
	}
}
