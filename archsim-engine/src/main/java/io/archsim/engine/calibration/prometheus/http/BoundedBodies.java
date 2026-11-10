package io.archsim.engine.calibration.prometheus.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;

import io.archsim.engine.calibration.prometheus.PrometheusException;

/**
 * An answer's body read within the size cap and the deadline, as the Python engine reads
 * it: refused before the body when its announced length is over the cap, else read a
 * chunk at a time until it ends, goes over the cap, or the deadline passes.
 */
final class BoundedBodies {

	/** How much of a body is read at a time. */
	private static final int CHUNK_BYTES = 64 * 1024;

	private BoundedBodies() {
	}

	/**
	 * Reads an answer's body. Its stream is not closed here: closing it would read the
	 * rest.
	 * @param response the answer
	 * @param server the server, its URL and size cap
	 * @param deadline when the query must end
	 * @return the body's bytes
	 * @throws PrometheusException when the body is over the cap
	 * @throws IOException when it cannot be read, or the deadline passes
	 */
	static byte[] read(ClassicHttpResponse response, PrometheusServer server, Deadline deadline) throws IOException {
		checkLength(response, server);
		HttpEntity entity = response.getEntity();
		if (entity == null) {
			return new byte[0];
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		InputStream in = entity.getContent();
		byte[] chunk = new byte[CHUNK_BYTES];
		int read = 0;
		while (read >= 0) {
			if (deadline.passed()) {
				throw new InterruptedIOException("past the deadline");
			}
			read = in.read(chunk);
			if (read > 0) {
				body.write(chunk, 0, read);
			}
			if (body.size() > server.maxBytes()) {
				throw tooLarge(server);
			}
		}
		if (deadline.cancelled()) {
			// the watchdog ended the body: it is not all there
			throw new InterruptedIOException("past the deadline");
		}
		return body.toByteArray();
	}

	/**
	 * Refuses an answer whose announced length is over the cap, before its body: the
	 * {@code Content-Length} values joined as Python's {@code getheader} joins them, read
	 * when they are digits only.
	 * @param response the answer
	 * @param server the server, its URL and size cap
	 * @throws PrometheusException when it announces more than the cap
	 */
	private static void checkLength(ClassicHttpResponse response, PrometheusServer server) {
		String length = Arrays.stream(response.getHeaders(HttpHeaders.CONTENT_LENGTH))
			.map(Header::getValue)
			.collect(Collectors.joining(", "));
		boolean digits = !length.isEmpty() && length.chars().allMatch((digit) -> digit >= '0' && digit <= '9');
		if (digits && new BigInteger(length).compareTo(BigInteger.valueOf(server.maxBytes())) > 0) {
			throw tooLarge(server);
		}
	}

	private static PrometheusException tooLarge(PrometheusServer server) {
		return new PrometheusException("the response from " + server.url() + " is larger than "
				+ String.format(Locale.ROOT, "%,d", server.maxBytes())
				+ " bytes: use a larger step or a shorter range");
	}

}
