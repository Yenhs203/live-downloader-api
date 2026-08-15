package com.vhmedia.livedownloader.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentDispositionFilenamesTest {

	@Test
	void attachmentQuotesSanitizedName() {
		assertThat(ContentDispositionFilenames.attachment("edit.mp4"))
				.isEqualTo("attachment; filename=\"edit.mp4\"");
	}

	@Test
	void stripsQuotesAndControlCharacters() {
		assertThat(ContentDispositionFilenames.attachment("clip\".mp4\r\n"))
				.isEqualTo("attachment; filename=\"clip.mp4\"");
	}

	@Test
	void rejectsPathAndFallsBack() {
		assertThat(ContentDispositionFilenames.inline("../../etc/passwd"))
				.isEqualTo("inline; filename=\"video.mp4\"");
	}
}
