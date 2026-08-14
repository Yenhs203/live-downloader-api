package com.vhmedia.livedownloader.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class MediaHttpFailureClassifierTest {

	@Test
	void classifiesHttpStatusCodes() {
		assertThat(MediaHttpFailureClassifier.classify("Server returned 403 Forbidden", null))
				.isEqualTo(MediaHttpFailureKind.HTTP_403);
		assertThat(MediaHttpFailureClassifier.classify("HTTP error 404 Not Found", null))
				.isEqualTo(MediaHttpFailureKind.HTTP_404);
	}

	@Test
	void classifiesTimeoutAndNetwork() {
		assertThat(MediaHttpFailureClassifier.classify("Connection timed out", null))
				.isEqualTo(MediaHttpFailureKind.TIMEOUT);
		assertThat(MediaHttpFailureClassifier.classify("Could not resolve host: cdn.example.com", null))
				.isEqualTo(MediaHttpFailureKind.DNS_OR_NETWORK);
		assertThat(MediaHttpFailureClassifier.classify("Connection refused", null))
				.isEqualTo(MediaHttpFailureKind.DNS_OR_NETWORK);
	}

	@Test
	void classifiesInvalidMediaAndMissingExecutable() {
		assertThat(MediaHttpFailureClassifier.classify("Invalid data found when processing input", null))
				.isEqualTo(MediaHttpFailureKind.INVALID_MEDIA);
		assertThat(MediaHttpFailureClassifier.classifyStartFailure(
				new IOException("Cannot run program \"ffprobe\": CreateProcess error=2, The system cannot find the file specified")
		)).isEqualTo(MediaHttpFailureKind.EXECUTABLE_MISSING);
	}

	@Test
	void formatRefererHeaderAlwaysEndsWithCrlf() {
		assertThat(MediaHttpRequestArgs.formatRefererHeader("https://www.tiktok.com/"))
				.isEqualTo("Referer: https://www.tiktok.com/\r\n");
		assertThat(MediaHttpRequestArgs.formatRefererHeader("Referer: https://www.tiktok.com/\r\n"))
				.isEqualTo("Referer: https://www.tiktok.com/\r\n");
	}
}
