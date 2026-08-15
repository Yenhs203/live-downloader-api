package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.exception.ErrorCode;
import com.vhmedia.livedownloader.exception.UploadTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CappedFileCopyTest {

	@TempDir
	Path tempDir;

	@Test
	void streamsWithoutExceedingCap() throws Exception {
		Path dest = tempDir.resolve("out.bin");
		byte[] payload = new byte[]{1, 2, 3, 4};

		long written = CappedFileCopy.copy(new ByteArrayInputStream(payload), dest, 4);

		assertThat(written).isEqualTo(4);
		assertThat(Files.readAllBytes(dest)).isEqualTo(payload);
	}

	@Test
	void deletesPartialFileWhenCapExceeded() {
		Path dest = tempDir.resolve("too-big.bin");
		byte[] payload = new byte[]{1, 2, 3, 4, 5};

		assertThatThrownBy(() -> CappedFileCopy.copy(new ByteArrayInputStream(payload), dest, 4))
				.isInstanceOf(UploadTooLargeException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPLOAD_TOO_LARGE);
		assertThat(Files.exists(dest)).isFalse();
	}

	@Test
	void editorCapUsesEditorUploadTooLargeCode() {
		Path dest = tempDir.resolve("editor-too-big.bin");
		byte[] payload = new byte[]{1, 2, 3, 4, 5};

		assertThatThrownBy(() -> CappedFileCopy.copy(
				new ByteArrayInputStream(payload),
				dest,
				4,
				ErrorCode.EDITOR_UPLOAD_TOO_LARGE
		))
				.isInstanceOf(UploadTooLargeException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.EDITOR_UPLOAD_TOO_LARGE);
		assertThat(Files.exists(dest)).isFalse();
	}
}
