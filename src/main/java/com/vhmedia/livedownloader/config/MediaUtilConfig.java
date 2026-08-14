package com.vhmedia.livedownloader.config;

import com.vhmedia.livedownloader.util.HostAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

@Configuration
public class MediaUtilConfig {

	@Bean
	HostAddressResolver hostAddressResolver() {
		return InetAddress::getAllByName;
	}
}
