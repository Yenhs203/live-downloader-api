package com.vhmedia.livedownloader;

import com.vhmedia.livedownloader.config.CorsProperties;
import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication
@EnableConfigurationProperties({CorsProperties.class, MediaProperties.class, SecurityProperties.class, EditorProperties.class})
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class LiveDownloaderApplication {

	public static void main(String[] args) {
		SpringApplication.run(LiveDownloaderApplication.class, args);
	}
}
