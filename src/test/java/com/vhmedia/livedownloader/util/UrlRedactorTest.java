package com.vhmedia.livedownloader.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlRedactorTest {

	@Test
	void redactsQueryStringWithToken() {
		String input = "https://host/path/live.flv?token=abc&expire=123";
		assertThat(UrlRedactor.redact(input)).isEqualTo("https://host/path/live.flv?[REDACTED]");
	}

	@ParameterizedTest
	@CsvSource({
			"https://cdn.example.com/live/index.m3u8?sig=xyz,https://cdn.example.com/live/index.m3u8?[REDACTED]",
			"http://cdn.example.com:8080/a.flv?a=1&b=2,http://cdn.example.com:8080/a.flv?[REDACTED]"
	})
	void redactsAnyQueryString(String input, String expected) {
		assertThat(UrlRedactor.redact(input)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://cdn.example.com/live/index.m3u8",
			"http://cdn.example.com/stream.flv"
	})
	void leavesUrlWithoutQueryUnchanged(String input) {
		assertThat(UrlRedactor.redact(input)).isEqualTo(input);
	}

	@Test
	void redactsQueryParamsInsideFreeFormText() {
		String input = "Failed for https://cdn.example.com/live.flv?token=secret&exp=1 detail";
		assertThat(UrlRedactor.redactInText(input))
				.isEqualTo("Failed for https://cdn.example.com/live.flv?[REDACTED] detail");
	}
}
