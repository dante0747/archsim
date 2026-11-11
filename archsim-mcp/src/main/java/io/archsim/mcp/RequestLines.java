package io.archsim.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;

/**
 * The server's standard input as the Python SDK read it: one JSON-RPC message per line,
 * of any length, and a line that is not one (not JSON, not an object, not a message)
 * skipped, as Python skipped it. The SDK's transport stops reading at such a line, so it
 * reads its messages from here and never sees one. The end of the input, or a failure to
 * read it, ends the session.
 */
final class RequestLines extends InputStream {

	/** What an object's JSON starts with. */
	private static final char OBJECT_START = '{';

	private final BufferedReader lines;

	private final McpJsonMapper json;

	private final CountDownLatch ended = new CountDownLatch(1);

	/** The message being read, with its line feed. */
	private byte[] message = new byte[0];

	/** Where the next byte of {@link #message} is. */
	private int next;

	/**
	 * The messages of an input.
	 * @param input the input, UTF-8
	 * @param json the SDK's JSON, which reads each message
	 */
	RequestLines(InputStream input, McpJsonMapper json) {
		this.lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
		this.json = json;
	}

	@Override
	public int read() throws IOException {
		if (!hasMore()) {
			return -1;
		}
		return Byte.toUnsignedInt(this.message[this.next++]);
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		Objects.checkFromIndexSize(offset, length, buffer.length);
		if (length == 0) {
			return 0;
		}
		if (!hasMore()) {
			return -1;
		}
		int count = Math.min(length, this.message.length - this.next);
		System.arraycopy(this.message, this.next, buffer, offset, count);
		this.next += count;
		return count;
	}

	@Override
	public void close() throws IOException {
		this.lines.close();
	}

	/**
	 * Waits until the input ends, or cannot be read.
	 * @throws InterruptedException when the wait is interrupted
	 */
	void awaitEnd() throws InterruptedException {
		this.ended.await();
	}

	/**
	 * Waits until the input ends, or cannot be read, at most for a while.
	 * @param timeout the longest wait
	 * @return whether it ended
	 * @throws InterruptedException when the wait is interrupted
	 */
	boolean awaitEnd(Duration timeout) throws InterruptedException {
		return this.ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	// whether a byte is left: of this message, or of the next line that is one
	private boolean hasMore() throws IOException {
		while (this.next == this.message.length) {
			String line = nextLine();
			if (line == null) {
				this.ended.countDown();
				return false;
			}
			if (isMessage(line)) {
				this.message = (line + "\n").getBytes(StandardCharsets.UTF_8);
				this.next = 0;
			}
		}
		return true;
	}

	private @Nullable String nextLine() throws IOException {
		try {
			return this.lines.readLine();
		}
		catch (IOException ex) {
			this.ended.countDown();
			throw ex;
		}
	}

	// an object the SDK reads as a message; JSON null, which it would fail on, is not one
	private boolean isMessage(String line) {
		String text = line.strip();
		if (text.isEmpty() || text.charAt(0) != OBJECT_START) {
			return false;
		}
		try {
			McpSchema.deserializeJsonRpcMessage(this.json, line);
			return true;
		}
		catch (IOException | IllegalArgumentException ex) {
			return false;
		}
	}

}
