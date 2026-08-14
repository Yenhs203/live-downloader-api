package com.vhmedia.livedownloader.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds baseline HTTP security headers. Not a substitute for authentication or a WAF.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityHeadersFilter extends OncePerRequestFilter {

	static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";
	/**
	 * Swagger UI needs same-origin scripts/styles/XHR; keep framing locked down.
	 */
	static final String SWAGGER_CSP =
			"default-src 'self'; "
					+ "script-src 'self'; "
					+ "style-src 'self' 'unsafe-inline'; "
					+ "img-src 'self' data:; "
					+ "font-src 'self'; "
					+ "connect-src 'self'; "
					+ "worker-src 'self' blob:; "
					+ "frame-ancestors 'none'; "
					+ "base-uri 'self'";

	private final SecurityProperties securityProperties;

	public SecurityHeadersFilter(SecurityProperties securityProperties) {
		this.securityProperties = securityProperties;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		if (securityProperties.isSecurityHeadersEnabled()) {
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.setHeader("X-Frame-Options", "DENY");
			response.setHeader("Referrer-Policy", "no-referrer");
			response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
			response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
			response.setHeader("Cross-Origin-Resource-Policy", "same-site");

			String path = request.getRequestURI() == null ? "" : request.getRequestURI();
			if (isSwaggerOrOpenApiPath(path)) {
				response.setHeader("Content-Security-Policy", SWAGGER_CSP);
			} else {
				// Strict CSP for API JSON responses; Swagger UI is disabled in prod.
				response.setHeader("Content-Security-Policy", API_CSP);
			}

			if (path.startsWith("/api/")) {
				response.setHeader("Cache-Control", "no-store");
			}
		}
		filterChain.doFilter(request, response);
	}

	static boolean isSwaggerOrOpenApiPath(String path) {
		return path.startsWith("/swagger-ui")
				|| path.equals("/swagger-ui.html")
				|| path.startsWith("/v3/api-docs")
				|| path.startsWith("/webjars/");
	}
}
