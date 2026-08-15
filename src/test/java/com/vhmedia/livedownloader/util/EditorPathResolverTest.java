package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorPathResolverTest {

	@TempDir
	Path tempDir;

	private EditorPathResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = resolverWithStorage(null);
	}

	@Test
	void generatesSourceAssetsExportsUnderProjectId() {
		UUID projectId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		UUID exportId = UUID.randomUUID();

		Path source = resolver.newSourceFile(projectId);
		Path asset = resolver.assetFile(projectId, assetId, "png");
		Path export = resolver.exportFile(projectId, exportId);
		Path tmp = resolver.tempExportFile(projectId, exportId);

		assertThat(source.getParent().getFileName().toString()).isEqualTo("source");
		assertThat(asset.getParent().getFileName().toString()).isEqualTo("assets");
		assertThat(export.getParent().getFileName().toString()).isEqualTo("exports");
		assertThat(tmp.getParent().getFileName().toString()).isEqualTo("tmp");
		assertThat(source.getFileName().toString()).endsWith(".mp4");
		assertThat(source.getFileName().toString()).isNotEqualTo("upload.mp4");
		assertThat(asset.getFileName().toString()).isEqualTo(assetId + ".png");
		assertThat(export.getFileName().toString()).isEqualTo(exportId + ".mp4");
		assertThat(source.startsWith(resolver.projectDirectory(projectId))).isTrue();
	}

	@Test
	void storedPathsAreRelativeAndRejectTraversal() {
		UUID projectId = UUID.randomUUID();
		Path source = resolver.newSourceFile(projectId);
		String stored = resolver.toStoredPath(source);

		assertThat(stored).doesNotContain("..");
		assertThat(stored).startsWith(projectId + "/source/");
		assertThat(resolver.toPath(stored)).isEqualTo(source.toAbsolutePath().normalize());
		assertThatThrownBy(() -> resolver.toPath("../outside.mp4"))
				.isInstanceOf(StorageException.class)
				.hasMessageContaining("traversal");
		assertThatThrownBy(() -> resolver.toPath(tempDir.getParent().resolve("outside.mp4").toString()))
				.isInstanceOf(StorageException.class)
				.hasMessageContaining("escapes");
	}

	@Test
	void rejectsUnsafeAssetExtension() {
		UUID projectId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		assertThatThrownBy(() -> resolver.assetFile(projectId, assetId, "../png"))
				.isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> resolver.assetFile(projectId, assetId, "png/../../x"))
				.isInstanceOf(StorageException.class);
	}

	@Test
	void importsRecordingWithHardLinkOrCopyWithoutDuplicatingWhenLinkWorks() throws Exception {
		UUID projectId = UUID.randomUUID();
		resolver.createProjectDirectory(projectId);
		Path recording = tempDir.resolve("completed.mp4");
		Files.write(recording, new byte[]{1, 2, 3, 4, 5});
		Path dest = resolver.newSourceFile(projectId);

		RecordingImportMode mode = resolver.importRecordingSource(projectId, recording, dest);

		assertThat(mode).isIn(RecordingImportMode.HARDLINK, RecordingImportMode.COPY);
		assertThat(Files.size(dest)).isEqualTo(5);
		if (mode == RecordingImportMode.HARDLINK) {
			Files.delete(recording);
			assertThat(Files.readAllBytes(dest)).containsExactly(1, 2, 3, 4, 5);
		}
	}

	@Test
	void deleteProjectDirectoryDoesNotRemoveRecordingOriginal() throws Exception {
		UUID projectId = UUID.randomUUID();
		resolver.createProjectDirectory(projectId);
		Path recording = tempDir.resolve("owned-by-recording.mp4");
		Files.write(recording, new byte[]{9, 8, 7});
		Path dest = resolver.newSourceFile(projectId);
		RecordingImportMode mode = resolver.importRecordingSource(projectId, recording, dest);
		assertThat(mode).isIn(RecordingImportMode.HARDLINK, RecordingImportMode.COPY);

		resolver.deleteProjectDirectory(projectId);

		assertThat(Files.exists(recording)).isTrue();
		assertThat(Files.readAllBytes(recording)).containsExactly(9, 8, 7);
		assertThat(Files.exists(resolver.projectDirectory(projectId))).isFalse();
		assertThat(resolver.isRecordingOriginalPath(recording)).isTrue();
		assertThat(resolver.isRecordingOriginalPath(dest)).isFalse();
	}

	@Test
	void usesConfiguredStorageDirectory() {
		Path custom = tempDir.resolve("custom-editor");
		EditorPathResolver customResolver = resolverWithStorage(custom.toString());
		UUID projectId = UUID.randomUUID();
		Path source = customResolver.newSourceFile(projectId);
		assertThat(source.startsWith(custom.toAbsolutePath().normalize())).isTrue();
		assertThat(source.toString()).contains("source");
	}

	@Test
	void deleteProjectDirectoryRemovesFiles() throws Exception {
		UUID id = UUID.randomUUID();
		resolver.createProjectDirectory(id);
		Path source = resolver.newSourceFile(id);
		Files.writeString(source, "x");
		assertThat(Files.exists(source)).isTrue();

		resolver.deleteProjectDirectory(id);

		assertThat(Files.exists(resolver.projectDirectory(id))).isFalse();
	}

	@Test
	void cleanupTempRemovesOnlyTmpFiles() throws Exception {
		UUID projectId = UUID.randomUUID();
		UUID exportId = UUID.randomUUID();
		resolver.createProjectDirectory(projectId);
		Path tmp = resolver.tempExportFile(projectId, exportId);
		Files.writeString(tmp, "partial");
		Path source = resolver.newSourceFile(projectId);
		Files.writeString(source, "keep");

		resolver.cleanupTemp(projectId);

		assertThat(Files.exists(tmp)).isFalse();
		assertThat(Files.exists(source)).isTrue();
	}

	private EditorPathResolver resolverWithStorage(String storageDirectory) {
		MediaProperties properties = new MediaProperties();
		properties.setRecordingsDirectory(tempDir.toString());
		properties.setMinFreeDiskBytes(0);
		RecordingPathResolver recordingPathResolver = new RecordingPathResolver(properties);
		EditorProperties editorProperties = new EditorProperties();
		editorProperties.setStorageDirectory(storageDirectory == null ? "" : storageDirectory);
		editorProperties.setMinFreeDiskBytes(0);
		return new EditorPathResolver(recordingPathResolver, editorProperties);
	}
}
