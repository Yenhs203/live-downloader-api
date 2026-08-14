package com.vhmedia.livedownloader.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

	/**
	 * Explicit browser origins allowed to call the API.
	 * Must never contain {@code *} — configure concrete origins per environment.
	 */
	@NotEmpty
	private List<String> allowedOrigins = new ArrayList<>();
}
