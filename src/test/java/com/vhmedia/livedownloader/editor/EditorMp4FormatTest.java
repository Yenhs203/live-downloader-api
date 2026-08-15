package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidEditorFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorMp4FormatTest {

	@Test
	void acceptsFtypSignatureRegardlessOfExtension(@TempDir Path tempDir) throws Exception {
		byte[] header = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
		Path disguised = tempDir.resolve("clip.bin");
		Files.write(disguised, header);

		EditorMp4Format.assertMp4(header);
		EditorMp4Format.assertMp4(disguised);
	}

	@Test
	void rejectsHtmlAndGifSignatures() {
		assertThatThrownBy(() -> EditorMp4Format.assertMp4("<!DOCTYPE html>".getBytes()))
				.isInstanceOf(InvalidEditorFileException.class)
				.hasMessageContaining("MP4");
		assertThatThrownBy(() -> EditorMp4Format.assertMp4(new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0}))
				.isInstanceOf(InvalidEditorFileException.class);
	}

	@Test
	void contentTypeWhitelistAcceptsMp4AndRejectsHtml() {
		assertThat(EditorMp4Format.isAllowedContentType(null)).isTrue();
		assertThat(EditorMp4Format.isAllowedContentType("video/mp4")).isTrue();
		assertThat(EditorMp4Format.isAllowedContentType("application/mp4; charset=binary")).isTrue();
		assertThat(EditorMp4Format.isAllowedContentType("text/html")).isFalse();
	}
}
