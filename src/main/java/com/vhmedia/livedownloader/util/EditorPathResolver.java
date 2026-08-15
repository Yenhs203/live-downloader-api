package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.exception.EditorStorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Resolves editor files under {@code {editorRoot}/{projectId}/source|assets|exports|tmp/}.
 * Storage basenames are always generated server-side. Client filenames are never used as paths.
 */
@Slf4j
@Component
public class EditorPathResolver {

	static final String DEFAULT_EDITOR_DIR = "editor";
	static final String SOURCE_DIR = "source";
	static final String ASSETS_DIR = "assets";
	static final String EXPORTS_DIR = "exports";
	static final String TMP_DIR = "tmp";
	static final String LEGACY_SOURCE_FILE = "source.mp4";
	static final String LEGACY_OUTPUT_FILE = "output.mp4";

	private final Path recordingsRoot;
	private final Path editorRoot;
	private final long minFreeDiskBytes;

	public EditorPathResolver(RecordingPathResolver recordingPathResolver, EditorProperties editorProperties) {
		this.recordingsRoot = recordingPathResolver.getRecordingsRoot();
		this.minFreeDiskBytes = editorProperties.getMinFreeDiskBytes();
		this.editorRoot = resolveEditorRoot(editorProperties.getStorageDirectory(), this.recordingsRoot);
		try {
			Files.createDirectories(this.editorRoot);
			assertWritable(this.editorRoot);
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to initialize editor directory", ex);
		}
	}

	public Path getEditorRoot() {
		return editorRoot;
	}

	public Path projectDirectory(UUID projectId) {
		Path dir = editorRoot.resolve(requireId(projectId).toString()).toAbsolutePath().normalize();
		assertInsideEditorRoot(dir);
		return dir;
	}

	public Path sourceDirectory(UUID projectId) {
		return subdirectory(projectId, SOURCE_DIR);
	}

	public Path assetsDirectory(UUID projectId) {
		return subdirectory(projectId, ASSETS_DIR);
	}

	public Path exportsDirectory(UUID projectId) {
		return subdirectory(projectId, EXPORTS_DIR);
	}

	public Path tmpDirectory(UUID projectId) {
		return subdirectory(projectId, TMP_DIR);
	}

	/**
	 * New source MP4. Basename is a generated UUID, never the client filename.
	 */
	public Path newSourceFile(UUID projectId) {
		return fileIn(sourceDirectory(projectId), UUID.randomUUID() + ".mp4");
	}

	/**
	 * Import a completed recording into the editor workspace.
	 * Prefers a hard link (no extra GB). Deleting the editor link never deletes the recording original.
	 * Falls back to copy when the filesystem rejects the link; the editor then owns that copy.
	 */
	public RecordingImportMode importRecordingSource(UUID projectId, Path recordingMp4, Path dest) {
		Path src = recordingMp4.toAbsolutePath().normalize();
		Path target = dest.toAbsolutePath().normalize();
		if (!src.startsWith(recordingsRoot)) {
			throw new EditorStorageException("Recording source escapes recordings directory");
		}
		assertProjectSourceFile(projectId, target);
		try {
			Files.createDirectories(target.getParent());
			Files.createLink(target, src);
			return RecordingImportMode.HARDLINK;
		} catch (IOException ignored) {
			try {
				Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
				return RecordingImportMode.COPY;
			} catch (IOException ex) {
				throw new EditorStorageException("Unable to import recording into editor workspace", ex);
			}
		}
	}

	public Path assetFile(UUID projectId, UUID assetId, String extension) {
		return fileIn(assetsDirectory(projectId), requireId(assetId) + "." + sanitizeExtension(extension));
	}

	public Path exportFile(UUID projectId, UUID exportJobId) {
		return fileIn(exportsDirectory(projectId), requireId(exportJobId) + ".mp4");
	}

	public Path tempExportFile(UUID projectId, UUID exportJobId) {
		return fileIn(tmpDirectory(projectId), requireId(exportJobId) + ".mp4");
	}

	/**
	 * @deprecated layout is now {@code source/{uuid}.mp4}; kept for tests/legacy checks
	 */
	public Path sourceMp4(UUID projectId) {
		return fileIn(sourceDirectory(projectId), LEGACY_SOURCE_FILE);
	}

	/**
	 * @deprecated layout is now {@code exports/{jobId}.mp4}
	 */
	public Path outputMp4(UUID projectId) {
		return fileIn(exportsDirectory(projectId), LEGACY_OUTPUT_FILE);
	}

	public Path toPath(String storedPath) {
		if (storedPath == null || storedPath.isBlank()) {
			throw new EditorStorageException("stored path must not be blank");
		}
		if (containsTraversal(storedPath)) {
			throw new EditorStorageException("Stored path contains path traversal");
		}
		Path path = Path.of(storedPath);
		if (!path.isAbsolute()) {
			path = editorRoot.resolve(path).toAbsolutePath().normalize();
			assertInsideEditorRoot(path);
			return path;
		}
		path = path.toAbsolutePath().normalize();
		if (path.startsWith(editorRoot)) {
			return path;
		}
		Path legacyEditor = recordingsRoot.resolve(DEFAULT_EDITOR_DIR).toAbsolutePath().normalize();
		if (path.startsWith(legacyEditor)) {
			return path;
		}
		throw new EditorStorageException("Stored path escapes editor directory");
	}

	public String toStoredPath(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		assertInsideEditorRoot(normalized);
		return editorRoot.relativize(normalized).toString().replace('\\', '/');
	}

	public void assertProjectSourceFile(UUID projectId, Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		assertInsideProject(projectId, normalized);
		Path sourceDir = sourceDirectory(projectId);
		Path legacy = projectDirectory(projectId).resolve(LEGACY_SOURCE_FILE).toAbsolutePath().normalize();
		if (normalized.startsWith(sourceDir) || normalized.equals(legacy)) {
			return;
		}
		throw new EditorStorageException("Editor source path is outside the project source directory");
	}

	public void assertProjectAssetFile(UUID projectId, Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		assertInsideProject(projectId, normalized);
		if (!normalized.startsWith(assetsDirectory(projectId))) {
			throw new EditorStorageException("Editor asset path is outside the project assets directory");
		}
	}

	public void assertProjectExportFile(UUID projectId, Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		assertInsideProject(projectId, normalized);
		Path exportsDir = exportsDirectory(projectId);
		Path legacy = projectDirectory(projectId).resolve(LEGACY_OUTPUT_FILE).toAbsolutePath().normalize();
		if (normalized.startsWith(exportsDir) || normalized.equals(legacy)) {
			return;
		}
		throw new EditorStorageException("Editor export path is outside the project exports directory");
	}

	public void createProjectDirectory(UUID projectId) {
		try {
			Files.createDirectories(sourceDirectory(projectId));
			Files.createDirectories(assetsDirectory(projectId));
			Files.createDirectories(exportsDirectory(projectId));
			Files.createDirectories(tmpDirectory(projectId));
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to create editor project directory", ex);
		}
	}

	public void deleteProjectDirectory(UUID projectId) {
		Path dir = projectDirectory(projectId);
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(this::deleteEditorOwnedFile);
		} catch (EditorStorageException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to delete editor project directory", ex);
		}
	}

	/**
	 * Unlink/delete a path only if it lives under the editor root.
	 * Recording originals (under the recordings root but outside editor/) are never deleted.
	 */
	void deleteEditorOwnedFile(Path path) {
		if (path == null) {
			return;
		}
		Path normalized = path.toAbsolutePath().normalize();
		if (isRecordingOriginalPath(normalized)) {
			log.warn("Refusing to delete recording original from editor cleanup file={}", normalized.getFileName());
			return;
		}
		assertInsideEditorRoot(normalized);
		try {
			Files.deleteIfExists(normalized);
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to delete editor file " + normalized.getFileName(), ex);
		}
	}

	boolean isRecordingOriginalPath(Path path) {
		if (path == null) {
			return false;
		}
		Path normalized = path.toAbsolutePath().normalize();
		return normalized.startsWith(recordingsRoot) && !normalized.startsWith(editorRoot);
	}

	public void cleanupTemp(UUID projectId) {
		Path tmp = tmpDirectory(projectId);
		if (!Files.isDirectory(tmp)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(tmp)) {
			walk.sorted(Comparator.reverseOrder())
					.filter(path -> !path.equals(tmp))
					.forEach(this::deleteEditorOwnedFile);
		} catch (EditorStorageException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to cleanup editor temp directory", ex);
		}
	}

	public void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Path normalized = path.toAbsolutePath().normalize();
			if (isRecordingOriginalPath(normalized)) {
				return;
			}
			assertInsideEditorRoot(normalized);
			Files.deleteIfExists(normalized);
		} catch (IOException | RuntimeException ignored) {
			// best-effort
		}
	}

	public void moveReplace(Path source, Path dest) {
		Path from = source.toAbsolutePath().normalize();
		Path to = dest.toAbsolutePath().normalize();
		assertInsideEditorRoot(from);
		assertInsideEditorRoot(to);
		try {
			Files.createDirectories(to.getParent());
			try {
				Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to move editor file into place", ex);
		}
	}

	public void assertWritableAndHasFreeSpace() {
		try {
			assertWritable(editorRoot);
			if (minFreeDiskBytes <= 0) {
				return;
			}
			FileStore store = Files.getFileStore(editorRoot);
			long free = store.getUsableSpace();
			if (free < minFreeDiskBytes) {
				throw new EditorStorageException(
						"Insufficient disk space under " + editorRoot
								+ " (free=" + free + " bytes, required=" + minFreeDiskBytes + ")"
				);
			}
		} catch (IOException ex) {
			throw new EditorStorageException("Editor directory is not usable", ex);
		}
	}

	public boolean isEmptyDirectory(Path dir) {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			return !stream.iterator().hasNext();
		} catch (IOException ex) {
			return false;
		}
	}

	private Path subdirectory(UUID projectId, String name) {
		Path dir = projectDirectory(projectId).resolve(name).toAbsolutePath().normalize();
		assertInsideEditorRoot(dir);
		if (!dir.getParent().equals(projectDirectory(projectId))) {
			throw new EditorStorageException("Resolved subdirectory escapes project directory");
		}
		return dir;
	}

	private Path fileIn(Path directory, String fileName) {
		if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
			throw new EditorStorageException("Invalid editor file name");
		}
		Path file = directory.resolve(fileName).toAbsolutePath().normalize();
		assertInsideEditorRoot(file);
		if (!file.getParent().equals(directory)) {
			throw new EditorStorageException("Resolved file escapes its directory");
		}
		return file;
	}

	private void assertInsideProject(UUID projectId, Path path) {
		assertInsideEditorRoot(path);
		if (!path.startsWith(projectDirectory(projectId))) {
			throw new EditorStorageException("Resolved path escapes editor project directory");
		}
	}

	private void assertInsideEditorRoot(Path path) {
		if (!path.startsWith(editorRoot)) {
			throw new EditorStorageException("Resolved path escapes editor directory");
		}
	}

	private static Path resolveEditorRoot(String configured, Path recordingsRoot) {
		if (configured == null || configured.isBlank()) {
			return recordingsRoot.resolve(DEFAULT_EDITOR_DIR).toAbsolutePath().normalize();
		}
		return Path.of(configured).toAbsolutePath().normalize();
	}

	private static void assertWritable(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			throw new EditorStorageException("Editor path is not a directory: " + dir);
		}
		if (!Files.isWritable(dir)) {
			throw new EditorStorageException("Editor directory is not writable: " + dir);
		}
		Path probe = dir.resolve(".write-probe-" + ProcessHandle.current().pid());
		try {
			Files.writeString(probe, "ok");
		} finally {
			Files.deleteIfExists(probe);
		}
	}

	private static boolean containsTraversal(String value) {
		return value.contains("..");
	}

	private static String sanitizeExtension(String extension) {
		if (extension == null || extension.isBlank()) {
			return "bin";
		}
		String trimmed = extension.trim().toLowerCase(Locale.ROOT);
		if (!trimmed.matches("[a-z0-9]{1,8}")) {
			throw new EditorStorageException("Invalid asset extension");
		}
		return trimmed;
	}

	private static UUID requireId(UUID id) {
		if (id == null) {
			throw new EditorStorageException("id must not be null");
		}
		return id;
	}
}
