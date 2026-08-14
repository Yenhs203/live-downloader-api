package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingPathResolverTest {

	@TempDir
	Path tempDir;

	private RecordingPathResolver resolver;

	@BeforeEach
	void setUp() {
		MediaProperties properties = new MediaProperties();
		properties.setRecordingsDirectory(tempDir.toString());
		properties.setMinFreeDiskBytes(0);
		resolver = new RecordingPathResolver(properties);
	}

	@Test
	void resolvesTsAndMp4InsideRoot() {
		assertThat(resolver.resolveTsPath("live_demo")).isEqualTo(tempDir.resolve("live_demo.ts").toAbsolutePath().normalize());
		assertThat(resolver.resolveMp4Path("live_demo")).isEqualTo(tempDir.resolve("live_demo.mp4").toAbsolutePath().normalize());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"../escape",
			"..\\escape",
			"a/b",
			"a\\b",
			"..",
			"foo/../bar"
	})
	void rejectsPathTraversalAttempts(String fileName) {
		assertThatThrownBy(() -> resolver.resolveInsideRoot(fileName))
				.isInstanceOf(StorageException.class);
	}

	@Test
	void rejectsBlankFileName() {
		assertThatThrownBy(() -> resolver.resolveInsideRoot("  "))
				.isInstanceOf(StorageException.class)
				.hasMessageContaining("blank");
	}

	@Test
	void toPathRejectsPathsOutsideRecordingsRoot() {
		Path outside = tempDir.getParent().resolve("outside.mp4").toAbsolutePath().normalize();
		assertThatThrownBy(() -> resolver.toPath(outside.toString()))
				.isInstanceOf(StorageException.class)
				.hasMessageContaining("escapes");
	}

	@Test
	void toPathAcceptsPathsInsideRoot() {
		Path inside = tempDir.resolve("ok.mp4").toAbsolutePath().normalize();
		assertThat(resolver.toPath(inside.toString())).isEqualTo(inside);
	}
}
