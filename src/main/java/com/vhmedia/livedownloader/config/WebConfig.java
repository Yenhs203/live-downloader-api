package com.vhmedia.livedownloader.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final CorsProperties corsProperties;

	public WebConfig(CorsProperties corsProperties) {
		this.corsProperties = corsProperties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		List<String> origins = corsProperties.getAllowedOrigins().stream()
				.filter(StringUtils::hasText)
				.map(String::trim)
				.distinct()
				.toList();

		if (origins.isEmpty()) {
			throw new IllegalStateException(
					"app.cors.allowed-origins must contain at least one origin");
		}

		for (String origin : origins) {
			if ("*".equals(origin)) {
				throw new IllegalStateException(
						"app.cors.allowed-origins must not use '*' — set explicit origins "
								+ "(for example http://localhost:4200 in development)");
			}
		}

		registry.addMapping("/api/**")
				.allowedOrigins(origins.toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
				.allowedHeaders(
						"Authorization",
						"Content-Type",
						"Accept",
						"Origin",
						"X-Requested-With",
						"Range")
				.exposedHeaders(
						"Content-Disposition",
						"Content-Length",
						"Content-Type",
						"Accept-Ranges",
						"Content-Range")
				.allowCredentials(true)
				.maxAge(3600);
	}
}
