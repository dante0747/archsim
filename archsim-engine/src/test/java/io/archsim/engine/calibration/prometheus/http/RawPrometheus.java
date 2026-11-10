package io.archsim.engine.calibration.prometheus.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A stand-in Prometheus on 127.0.0.1 that writes raw bytes, its status line included,
 * pausing between chunks, and then closes the connection: for answers a well-behaved HTTP
 * server never sends (trickled, cut off, not HTTP at all).
 */
final class RawPrometheus implements AutoCloseable {

	private final ServerSocket socket;

	private final List<byte[]> chunks;

	private final Duration pause;

	private RawPrometheus(List<byte[]> chunks, Duration pause) {
		this.chunks = List.copyOf(chunks);
		this.pause = pause;
		try {
			this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		Thread.ofVirtual().start(this::serve);
	}

	/**
	 * A server that writes text at once and closes.
	 * @param text the bytes, as ASCII
	 * @return the started server
	 */
	static RawPrometheus writing(String text) {
		return new RawPrometheus(List.of(text.getBytes(StandardCharsets.US_ASCII)), Duration.ZERO);
	}

	/**
	 * A server that writes text one byte at a time, from a byte on, pausing after each.
	 * @param text the bytes, as ASCII
	 * @param from how many bytes go at once before the trickle starts
	 * @param pause the pause after each chunk
	 * @return the started server
	 */
	static RawPrometheus trickling(String text, int from, Duration pause) {
		byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
		List<byte[]> chunks = new ArrayList<>();
		if (from > 0) {
			chunks.add(Arrays.copyOf(bytes, from));
		}
		for (int i = from; i < bytes.length; i++) {
			chunks.add(new byte[] { bytes[i] });
		}
		return new RawPrometheus(chunks, pause);
	}

	/**
	 * Its address.
	 * @return {@code http://127.0.0.1:<port>}
	 */
	String url() {
		return "http://127.0.0.1:" + this.socket.getLocalPort();
	}

	private void serve() {
		while (!this.socket.isClosed()) {
			try {
				Socket connection = this.socket.accept();
				Thread.ofVirtual().start(() -> answer(connection));
			}
			catch (IOException ex) {
				// closed
			}
		}
	}

	private void answer(Socket connection) {
		try (connection) {
			// the request line and headers
			connection.getInputStream().read(new byte[8192]);
			OutputStream out = connection.getOutputStream();
			for (byte[] chunk : this.chunks) {
				out.write(chunk);
				out.flush();
				Thread.sleep(this.pause);
			}
		}
		catch (IOException ex) {
			// the client gave up (the slow cases)
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() throws IOException {
		this.socket.close();
	}

}
