package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidEditorFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorImageFormatTest {

	@Test
	void detectsJpegPngAndWebpFromMagicBytesIgnoringExtension() {
		assertThat(EditorImageFormat.fromMagicBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}))
				.isEqualTo(EditorImageFormat.JPEG);
		assertThat(EditorImageFormat.fromMagicBytes(new byte[]{
				(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
		})).isEqualTo(EditorImageFormat.PNG);
		assertThat(EditorImageFormat.fromMagicBytes(new byte[]{
				'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
		})).isEqualTo(EditorImageFormat.WEBP);
	}

	@Test
	void detectUsesFileContentsNotTheName(@TempDir Path tempDir) throws Exception {
		Path disguised = tempDir.resolve("photo.jpg");
		Files.write(disguised, new byte[]{
				(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
		});
		assertThat(EditorImageFormat.detect(disguised)).isEqualTo(EditorImageFormat.PNG);
	}

	@Test
	void rejectsHtmlAndGifSignatures() {
		assertThatThrownBy(() -> EditorImageFormat.fromMagicBytes("<!DOCTYPE html>".getBytes()))
				.isInstanceOf(InvalidEditorFileException.class)
				.hasMessageContaining("JPEG, PNG, WEBP");
		assertThatThrownBy(() -> EditorImageFormat.fromMagicBytes(new byte[]{'G', 'I', 'F', '8', '9', 'a'}))
				.isInstanceOf(InvalidEditorFileException.class);
	}

	@Test
	void contentTypeWhitelistAcceptsImagesAndRejectsHtml() {
		assertThat(EditorImageFormat.isAllowedContentType(null)).isTrue();
		assertThat(EditorImageFormat.isAllowedContentType("image/png")).isTrue();
		assertThat(EditorImageFormat.isAllowedContentType("image/jpeg; charset=binary")).isTrue();
		assertThat(EditorImageFormat.isAllowedContentType("text/html")).isFalse();
	}
}
