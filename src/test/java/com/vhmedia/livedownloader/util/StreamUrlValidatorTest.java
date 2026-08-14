package com.vhmedia.livedownloader.util;

import com.vhmedia.livedownloader.config.SecurityProperties;
import com.vhmedia.livedownloader.exception.ErrorCode;
import com.vhmedia.livedownloader.exception.InvalidStreamUrlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamUrlValidatorTest {

	private SecurityProperties securityProperties;
	private StreamUrlValidator validator;

	@BeforeEach
	void setUp() {
		securityProperties = new SecurityProperties();
		securityProperties.setBlockPrivateStreamAddresses(false);
		validator = new StreamUrlValidator(securityProperties, host -> new InetAddress[0]);
	}

	@Nested
	@DisplayName("Basic validation")
	class BasicValidation {

		@Test
		void rejectsNull() {
			assertInvalid(null, "must not be null");
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = {"   ", "\t", "\n"})
		void rejectsBlank(String streamUrl) {
			if (streamUrl == null) {
				assertInvalid(null, "must not be null");
				return;
			}
			assertInvalid(streamUrl, "must not be blank");
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"http://cdn.example.com/live.flv",
				"https://cdn.example.com/live/index.m3u8",
				"HTTPS://CDN.EXAMPLE.COM/live.m3u8?token=abc&exp=123",
				"http://cdn.example.com:8080/stream.flv?sig=xyz",
				"  https://cdn.example.com/a.m3u8  "
		})
		void acceptsValidHttpAndHttpsUrls(String streamUrl) {
			assertThatCode(() -> validator.validate(streamUrl)).doesNotThrowAnyException();
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"not a url",
				"http://exa mple.com/live",
				"https://example.com/path with spaces",
				"://missing-scheme-host"
		})
		void rejectsMalformedUri(String streamUrl) {
			assertThatThrownBy(() -> validator.validate(streamUrl))
					.isInstanceOf(InvalidStreamUrlException.class)
					.satisfies(ex -> assertThat(((InvalidStreamUrlException) ex).getErrorCode())
							.isEqualTo(ErrorCode.INVALID_STREAM_URL));
		}

		@Test
		void rejectsMissingScheme() {
			assertInvalid("cdn.example.com/live.m3u8", "scheme");
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"file:///etc/passwd",
				"FILE://localhost/c:/windows/win.ini",
				"ftp://cdn.example.com/live.flv",
				"rtmp://cdn.example.com/live",
				"rtmps://cdn.example.com/live",
				"ws://cdn.example.com/live",
				"data:text/plain,hello"
		})
		void rejectsDisallowedSchemes(String streamUrl) {
			assertThatThrownBy(() -> validator.validate(streamUrl))
					.isInstanceOf(InvalidStreamUrlException.class);
		}

		@Test
		void rejectsFileUriWithExplicitMessage() {
			assertInvalid("file:///tmp/video.ts", "file://");
		}

		@Test
		void rejectsMissingHost() {
			assertInvalid("http:///path-only", "host");
		}

		@ParameterizedTest
		@ValueSource(strings = {"https://", "http://"})
		void rejectsUrlWithoutAuthority(String streamUrl) {
			assertThatThrownBy(() -> validator.validate(streamUrl))
					.isInstanceOf(InvalidStreamUrlException.class);
		}

		@Test
		void usesInvalidStreamUrlExceptionErrorCode() {
			assertThatThrownBy(() -> validator.validate("ftp://example.com/a"))
					.isInstanceOf(InvalidStreamUrlException.class)
					.extracting(ex -> ((InvalidStreamUrlException) ex).getErrorCode())
					.isEqualTo(ErrorCode.INVALID_STREAM_URL);
		}
	}

	@Nested
	@DisplayName("SSRF protection disabled")
	class SsrfDisabled {

		@Test
		void allowsLoopbackLiteralWhenProtectionDisabled() {
			assertThatCode(() -> validator.validate("http://127.0.0.1/live.m3u8"))
					.doesNotThrowAnyException();
		}

		@Test
		void allowsPrivateLanLiteralWhenProtectionDisabled() {
			assertThatCode(() -> validator.validate("http://192.168.1.10/live.flv"))
					.doesNotThrowAnyException();
		}

		@Test
		void doesNotResolveHostWhenProtectionDisabled() {
			HostAddressResolver failingResolver = host -> {
				throw new AssertionError("DNS must not be consulted when SSRF protection is disabled");
			};
			StreamUrlValidator localValidator = new StreamUrlValidator(securityProperties, failingResolver);

			assertThatCode(() -> localValidator.validate("https://internal.example.local/live.m3u8"))
					.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("SSRF protection enabled")
	class SsrfEnabled {

		@BeforeEach
		void enableProtection() {
			securityProperties.setBlockPrivateStreamAddresses(true);
		}

		@Test
		void rejectsLocalhostHostname() {
			validator = new StreamUrlValidator(securityProperties, host -> {
				throw new AssertionError("localhost should be rejected before DNS");
			});
			assertInvalid("http://localhost/live.m3u8", "localhost");
		}

		@Test
		void rejectsLocalhostCaseInsensitive() {
			validator = new StreamUrlValidator(securityProperties, host -> new InetAddress[0]);
			assertInvalid("https://LocalHost:8443/a.m3u8", "localhost");
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"127.0.0.1",
				"10.0.0.5",
				"172.16.0.1",
				"172.31.255.255",
				"192.168.0.1",
				"169.254.169.254",
				"0.0.0.0",
				"224.0.0.1"
		})
		void rejectsBlockedIpv4Destinations(String ip) throws Exception {
			InetAddress address = InetAddress.getByName(ip);
			validator = new StreamUrlValidator(securityProperties, host -> new InetAddress[]{address});

			assertInvalid("http://" + ip + "/live.m3u8", "private");
		}

		@Test
		void rejectsIpv6Loopback() throws Exception {
			InetAddress address = InetAddress.getByName("::1");
			validator = new StreamUrlValidator(securityProperties, host -> {
				assertThat(host).isEqualTo("::1");
				return new InetAddress[]{address};
			});

			assertInvalid("http://[::1]/live.m3u8", "private");
		}

		@Test
		void rejectsIpv6LinkLocal() throws Exception {
			InetAddress address = InetAddress.getByName("fe80::1");
			validator = new StreamUrlValidator(securityProperties, host -> new InetAddress[]{address});

			assertInvalid("http://[fe80::1]/live.m3u8", "private");
		}

		@Test
		void rejectsIpv6UniqueLocal() throws Exception {
			InetAddress address = InetAddress.getByName("fd12:3456:789a:1::1");
			assertThat(StreamUrlValidator.isBlockedAddress(address)).isTrue();

			validator = new StreamUrlValidator(securityProperties, host -> new InetAddress[]{address});
			assertInvalid("http://[fd12:3456:789a:1::1]/live.m3u8", "private");
		}

		@Test
		void rejectsHostnameThatResolvesToPrivateAddress() throws Exception {
			InetAddress privateAddress = InetAddress.getByName("10.1.2.3");
			validator = new StreamUrlValidator(
					securityProperties,
					host -> {
						assertThat(host).isEqualTo("stream.internal");
						return new InetAddress[]{privateAddress};
					}
			);

			assertInvalid("https://stream.internal/live.m3u8", "private");
		}

		@Test
		void rejectsIfAnyResolvedAddressIsPrivate() throws Exception {
			InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
			InetAddress privateAddress = InetAddress.getByName("192.168.10.20");
			validator = new StreamUrlValidator(
					securityProperties,
					host -> new InetAddress[]{publicAddress, privateAddress}
			);

			assertInvalid("https://mixed.example.com/live.m3u8", "private");
		}

		@Test
		void acceptsHostnameThatResolvesOnlyToPublicAddress() throws Exception {
			InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
			validator = new StreamUrlValidator(securityProperties, host -> new InetAddress[]{publicAddress});

			assertThatCode(() -> validator.validate("https://cdn.example.com/live/index.m3u8?token=1"))
					.doesNotThrowAnyException();
		}

		@Test
		void rejectsUnresolvableHost() {
			validator = new StreamUrlValidator(securityProperties, host -> {
				throw new UnknownHostException(host);
			});

			assertInvalid("https://does-not-resolve.invalid/live.m3u8", "resolve");
		}

		@Test
		void rejectsEmptyResolutionResult() {
			validator = new StreamUrlValidator(securityProperties, host -> new InetAddress[0]);

			assertInvalid("https://cdn.example.com/live.m3u8", "resolve");
		}
	}

	@Nested
	@DisplayName("Blocked address classification")
	class BlockedAddressClassification {

		@Test
		void classifiesCommonPrivateAndLocalAddresses() throws Exception {
			assertThat(StreamUrlValidator.isBlockedAddress(InetAddress.getByName("127.0.0.1"))).isTrue();
			assertThat(StreamUrlValidator.isBlockedAddress(InetAddress.getByName("10.0.0.1"))).isTrue();
			assertThat(StreamUrlValidator.isBlockedAddress(InetAddress.getByName("172.16.5.5"))).isTrue();
			assertThat(StreamUrlValidator.isBlockedAddress(InetAddress.getByName("192.168.1.1"))).isTrue();
			assertThat(StreamUrlValidator.isBlockedAddress(InetAddress.getByName("169.254.1.1"))).isTrue();
			assertThat(StreamUrlValidator.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
			assertThat(StreamUrlValidator.isBlockedAddress(InetAddress.getByName("1.1.1.1"))).isFalse();
		}

		@Test
		void stripsBracketsFromIpv6Host() {
			assertThat(StreamUrlValidator.normalizeHost("[::1]")).isEqualTo("::1");
			assertThat(StreamUrlValidator.normalizeHost("cdn.example.com")).isEqualTo("cdn.example.com");
			assertThat(StreamUrlValidator.normalizeHost(null)).isNull();
		}
	}

	private void assertInvalid(String streamUrl, String messageFragment) {
		assertThatThrownBy(() -> validator.validate(streamUrl))
				.isInstanceOf(InvalidStreamUrlException.class)
				.hasMessageContaining(messageFragment);
	}
}
