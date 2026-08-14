package com.vhmedia.livedownloader.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityHeadersFilterTest {

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private FilterChain filterChain;

	@Test
	void apiPathsUseStrictCsp() throws Exception {
		SecurityProperties properties = new SecurityProperties();
		properties.setSecurityHeadersEnabled(true);
		SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

		when(request.getRequestURI()).thenReturn("/api/v1/health");

		filter.doFilterInternal(request, response, filterChain);

		verify(response).setHeader("Content-Security-Policy", SecurityHeadersFilter.API_CSP);
		verify(response).setHeader("Cache-Control", "no-store");
		verify(filterChain).doFilter(request, response);
	}

	@Test
	void swaggerUiUsesRelaxedCsp() throws Exception {
		SecurityProperties properties = new SecurityProperties();
		properties.setSecurityHeadersEnabled(true);
		SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

		when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");

		filter.doFilterInternal(request, response, filterChain);

		ArgumentCaptor<String> csp = ArgumentCaptor.forClass(String.class);
		verify(response).setHeader(eq("Content-Security-Policy"), csp.capture());
		assertThat(csp.getValue()).isEqualTo(SecurityHeadersFilter.SWAGGER_CSP);
		assertThat(csp.getValue()).contains("script-src 'self'");
	}

	@Test
	void detectsSwaggerPaths() {
		assertThat(SecurityHeadersFilter.isSwaggerOrOpenApiPath("/swagger-ui/index.html")).isTrue();
		assertThat(SecurityHeadersFilter.isSwaggerOrOpenApiPath("/v3/api-docs")).isTrue();
		assertThat(SecurityHeadersFilter.isSwaggerOrOpenApiPath("/api/v1/health")).isFalse();
	}
}
