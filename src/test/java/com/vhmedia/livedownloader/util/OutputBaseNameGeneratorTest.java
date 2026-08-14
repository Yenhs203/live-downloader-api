package com.vhmedia.livedownloader.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputBaseNameGeneratorTest {

	@Test
	void generatesLiveTimestampShortUuidPattern() {
		String name = OutputBaseNameGenerator.generate();
		assertThat(name).matches("live_\\d{8}_\\d{6}_[a-f0-9]{8}");
	}
}
