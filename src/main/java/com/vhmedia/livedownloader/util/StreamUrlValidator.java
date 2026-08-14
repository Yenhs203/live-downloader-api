package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.config.SecurityProperties;
import com.vhmedia.livedownloader.exception.InvalidStreamUrlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Validates user-provided livestream URLs.
 * <p>
 * Validated URLs must never be used to construct filesystem paths or filenames.
 */
@Slf4j
@Component
public class StreamUrlValidator {

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	private final SecurityProperties securityProperties;
	private final HostAddressResolver hostAddressResolver;

	public StreamUrlValidator(SecurityProperties securityProperties, HostAddressResolver hostAddressResolver) {
		this.securityProperties = securityProperties;
		this.hostAddressResolver = hostAddressResolver;
	}

	/**
	 * Validates {@code streamUrl}. Throws {@link InvalidStreamUrlException} when invalid.
	 */
	public void validate(String streamUrl) {
		if (streamUrl == null) {
			throw new InvalidStreamUrlException("Stream URL must not be null");
		}

		String trimmed = streamUrl.trim();
		if (trimmed.isEmpty()) {
			throw new InvalidStreamUrlException("Stream URL must not be blank");
		}

		final URI uri;
		try {
			uri = new URI(trimmed);
		} catch (URISyntaxException ex) {
			throw new InvalidStreamUrlException("Stream URL is malformed: " + ex.getReason());
		}

		String scheme = uri.getScheme();
		if (scheme == null) {
			throw new InvalidStreamUrlException("Stream URL must include a scheme (http or https)");
		}

		String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
		if ("file".equals(normalizedScheme)) {
			throw new InvalidStreamUrlException("file:// URLs are not allowed");
		}
		if (!ALLOWED_SCHEMES.contains(normalizedScheme)) {
			throw new InvalidStreamUrlException("Only http and https stream URLs are allowed");
		}

		String host = normalizeHost(uri.getHost());
		if (host == null || host.isBlank()) {
			throw new InvalidStreamUrlException("Stream URL must include a valid host");
		}

		if (securityProperties.isBlockPrivateStreamAddresses()) {
			rejectPrivateOrLocalDestination(host);
		}
	}

	/**
	 * Some JDKs return IPv6 literals from {@link URI#getHost()} still wrapped in brackets.
	 */
	static String normalizeHost(String host) {
		if (host == null) {
			return null;
		}
		if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
			return host.substring(1, host.length() - 1);
		}
		return host;
	}

	private void rejectPrivateOrLocalDestination(String host) {
		if ("localhost".equalsIgnoreCase(host)) {
			throw new InvalidStreamUrlException("Stream URL host must not target localhost");
		}

		final InetAddress[] addresses;
		try {
			addresses = hostAddressResolver.resolve(host);
		} catch (UnknownHostException ex) {
			throw new InvalidStreamUrlException("Unable to resolve stream URL host: " + host);
		}

		if (addresses == null || addresses.length == 0) {
			throw new InvalidStreamUrlException("Unable to resolve stream URL host: " + host);
		}

		for (InetAddress address : addresses) {
			if (isBlockedAddress(address)) {
				log.warn("Rejected stream URL host={} address={} (private/local destination)", host, address.getHostAddress());
				throw new InvalidStreamUrlException(
						"Stream URL must not target loopback, link-local, or private network addresses"
				);
			}
		}
	}

	static boolean isBlockedAddress(InetAddress address) {
		return address.isAnyLocalAddress()
				|| address.isLoopbackAddress()
				|| address.isLinkLocalAddress()
				|| address.isSiteLocalAddress()
				|| address.isMulticastAddress()
				|| isUniqueLocalIpv6(address);
	}

	/**
	 * IPv6 Unique Local Addresses (fc00::/7), not always covered by {@link InetAddress#isSiteLocalAddress()}.
	 */
	private static boolean isUniqueLocalIpv6(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (bytes.length != 16) {
			return false;
		}
		return (bytes[0] & 0xFE) == 0xFC;
	}
}
