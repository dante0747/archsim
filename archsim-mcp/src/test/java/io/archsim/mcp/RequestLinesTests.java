package io.archsim.mcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/**
 * The server's input as the Python SDK read it: its messages, a line that is not one
 * skipped, and its end, or a failure to read it, the session's end.
 */
@Timeout(60)
class RequestLinesTests {

	private static final String MESSAGE = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}";

	@Test
	void read_linesThatAreNotMessages_areSkipped() throws IOException {
		RequestLines lines = lines("{not json\r\n{}\n\nnull\n[1]\n" + MESSAGE + "\r\n");

		assertThat(new String(lines.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(MESSAGE + "\n");
	}

	@Test
	void read_theEndOfTheInput_endsTheSession() throws IOException, InterruptedException {
		RequestLines lines = lines(MESSAGE);

		lines.readAllBytes();

		assertThat(lines.awaitEnd(Duration.ZERO)).isTrue();
	}

	@Test
	void read_anInputThatCannotBeRead_endsTheSession() throws InterruptedException {
		RequestLines lines = new RequestLines(new FailingInput(), ArchsimMcp.JSON);

		assertThatIOException().isThrownBy(lines::read);
		assertThat(lines.awaitEnd(Duration.ZERO)).isTrue();
	}

	@Test
	void awaitEnd_inputNotYetRead_isNotTheEnd() throws InterruptedException {
		assertThat(lines(MESSAGE).awaitEnd(Duration.ZERO)).isFalse();
	}

	private static RequestLines lines(String text) {
		return new RequestLines(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), ArchsimMcp.JSON);
	}

	/**
	 * An input whose every read fails, as a closed pipe's does.
	 */
	private static final class FailingInput extends InputStream {

		@Override
		public int read() throws IOException {
			throw new IOException("the pipe is closed");
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			throw new IOException("the pipe is closed");
		}

	}

}
