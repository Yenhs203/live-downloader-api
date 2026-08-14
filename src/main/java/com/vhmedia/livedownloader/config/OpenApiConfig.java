package com.vhmedia.livedownloader.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("VH MEDIA LIVE DOWNLOADER")
						.description("Livestream recording API (probe, record, SSE progress, download)")
						.version("v1"));
	}
}
