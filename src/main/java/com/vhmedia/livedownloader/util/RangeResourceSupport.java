package com.vhmedia.livedownloader.util;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Streams a {@link Resource} with HTTP Range support for large media files.
 * Uses Spring {@link Resource} (not {@code ResourceRegion}) so {@code video/mp4}
 * is written by the standard resource converter without buffering the file.
 */
public final class RangeResourceSupport {

	private RangeResourceSupport() {
	}

	public static ResponseEntity<Resource> toResponse(
			Resource resource,
			long contentLength,
			MediaType mediaType,
			String contentDisposition,
			HttpHeaders requestHeaders
	) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
		if (contentDisposition != null && !contentDisposition.isBlank()) {
			headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
		}

		List<HttpRange> ranges;
		try {
			ranges = requestHeaders == null ? List.of() : requestHeaders.getRange();
		} catch (IllegalArgumentException ex) {
			return unsatisfiable(contentLength, headers);
		}

		if (ranges == null || ranges.isEmpty()) {
			return ResponseEntity.ok()
					.headers(headers)
					.contentType(mediaType)
					.contentLength(Math.max(0L, contentLength))
					.body(resource);
		}

		if (contentLength <= 0) {
			return unsatisfiable(contentLength, headers);
		}

		HttpRange range = ranges.getFirst();
		long start;
		long end;
		try {
			start = range.getRangeStart(contentLength);
			end = range.getRangeEnd(contentLength);
		} catch (IllegalArgumentException ex) {
			return unsatisfiable(contentLength, headers);
		}
		if (start < 0 || start > end || start >= contentLength) {
			return unsatisfiable(contentLength, headers);
		}
		long count = end - start + 1;
		headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + contentLength);
		return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
				.headers(headers)
				.contentType(mediaType)
				.contentLength(count)
				.body(new ByteRangeResource(resource, start, count));
	}

	private static ResponseEntity<Resource> unsatisfiable(long contentLength, HttpHeaders headers) {
		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.putAll(headers);
		responseHeaders.set(HttpHeaders.CONTENT_RANGE, "bytes */" + Math.max(0L, contentLength));
		return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
				.headers(responseHeaders)
				.body(null);
	}

	static final class ByteRangeResource extends AbstractResource {

		private final Resource delegate;
		private final long start;
		private final long count;

		ByteRangeResource(Resource delegate, long start, long count) {
			this.delegate = delegate;
			this.start = start;
			this.count = count;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			InputStream in = delegate.getInputStream();
			try {
				in.skipNBytes(start);
				return new BoundedInputStream(in, count);
			} catch (IOException ex) {
				in.close();
				throw ex;
			}
		}

		@Override
		public long contentLength() {
			return count;
		}

		@Override
		public boolean exists() {
			return delegate.exists();
		}

		@Override
		public String getDescription() {
			return "byte range " + start + "+" + count + " of " + delegate.getDescription();
		}

		long start() {
			return start;
		}

		long count() {
			return count;
		}
	}

	private static final class BoundedInputStream extends FilterInputStream {

		private long remaining;

		private BoundedInputStream(InputStream in, long limit) {
			super(in);
			this.remaining = limit;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int value = super.read();
			if (value >= 0) {
				remaining--;
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int off, int len) throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int n = super.read(buffer, off, (int) Math.min(len, remaining));
			if (n > 0) {
				remaining -= n;
			}
			return n;
		}
	}
}
