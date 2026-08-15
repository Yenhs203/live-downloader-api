package com.vhmedia.livedownloader.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeOriginalFilenameTest {

	@Test
	void stripsPathAndRejectsTraversal() {
		assertThat(SafeOriginalFilename.displayName("uploads/clip.mp4")).isEqualTo("clip.mp4");
		assertThat(SafeOriginalFilename.displayName("../../etc/passwd")).isNull();
		assertThat(SafeOriginalFilename.displayName("..")).isNull();
		assertThat(SafeOriginalFilename.looksLikeMp4("folder/video.MP4")).isTrue();
		assertThat(SafeOriginalFilename.looksLikeMp4("video.exe")).isFalse();
	}
}
