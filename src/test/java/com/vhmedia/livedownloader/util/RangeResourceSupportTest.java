package com.vhmedia.livedownloader.util;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class RangeResourceSupportTest {

	private static final MediaType VIDEO = MediaType.parseMediaType("video/mp4");
	private static final String DISPOSITION = "inline; filename=\"source.mp4\"";

	@Test
	void fullResponseWhenNoRange() {
		Resource resource = bytes("0123456789");
		ResponseEntity<?> response = RangeResourceSupport.toResponse(
				resource,
				10,
				VIDEO,
				DISPOSITION,
				new HttpHeaders()
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
		assertThat(response.getHeaders().getContentLength()).isEqualTo(10L);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo(DISPOSITION);
		assertThat(response.getBody()).isSameAs(resource);
	}

	@Test
	void partialContentForByteRange() {
		ResponseEntity<?> response = RangeResourceSupport.toResponse(
				bytes("0123456789"),
				10,
				VIDEO,
				DISPOSITION,
				range("bytes=0-3")
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
		assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-3/10");
		assertThat(response.getHeaders().getContentLength()).isEqualTo(4L);
		assertThat(response.getBody()).isInstanceOf(RangeResourceSupport.ByteRangeResource.class);
		RangeResourceSupport.ByteRangeResource region = (RangeResourceSupport.ByteRangeResource) response.getBody();
		assertThat(region.start()).isEqualTo(0L);
		assertThat(region.count()).isEqualTo(4L);
	}

	@Test
	void openEndedRangeToEndOfFile() {
		ResponseEntity<?> response = RangeResourceSupport.toResponse(
				bytes("0123456789"),
				10,
				VIDEO,
				DISPOSITION,
				range("bytes=7-")
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 7-9/10");
		RangeResourceSupport.ByteRangeResource region = (RangeResourceSupport.ByteRangeResource) response.getBody();
		assertThat(region.start()).isEqualTo(7L);
		assertThat(region.count()).isEqualTo(3L);
	}

	@Test
	void unsatisfiableRangeReturns416() {
		ResponseEntity<?> response = RangeResourceSupport.toResponse(
				bytes("0123456789"),
				10,
				VIDEO,
				DISPOSITION,
				range("bytes=100-200")
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
		assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
	}

	private static Resource bytes(String value) {
		return new ByteArrayResource(value.getBytes());
	}

	private static HttpHeaders range(String value) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RANGE, value);
		return headers;
	}
}
