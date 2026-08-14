package com.vhmedia.livedownloader.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

	/**
	 * When true, stream URL hosts that resolve to loopback, link-local,
	 * or private LAN addresses are rejected to reduce SSRF risk.
	 */
	private boolean blockPrivateStreamAddresses = false;

	/**
	 * When true, responses include baseline security headers
	 * (X-Content-Type-Options, X-Frame-Options, Referrer-Policy, etc.).
	 */
	private boolean securityHeadersEnabled = true;
}
