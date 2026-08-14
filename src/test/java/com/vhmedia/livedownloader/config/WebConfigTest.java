package com.vhmedia.livedownloader.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class WebConfigTest {

	@Test
	void rejectsWildcardOrigins() {
		CorsProperties properties = new CorsProperties();
		properties.setAllowedOrigins(List.of("*"));
		WebConfig config = new WebConfig(properties);

		assertThatThrownBy(() -> config.addCorsMappings(new CorsRegistry()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must not use '*'");
	}

	@Test
	void rejectsEmptyOrigins() {
		CorsProperties properties = new CorsProperties();
		properties.setAllowedOrigins(List.of());
		WebConfig config = new WebConfig(properties);

		assertThatThrownBy(() -> config.addCorsMappings(new CorsRegistry()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must contain at least one origin");
	}

	@Test
	void registersExplicitDevOrigin() {
		CorsProperties properties = new CorsProperties();
		properties.setAllowedOrigins(List.of("http://localhost:4200"));
		WebConfig config = new WebConfig(properties);

		assertThatCode(() -> config.addCorsMappings(new CorsRegistry()))
				.doesNotThrowAnyException();
	}
}
