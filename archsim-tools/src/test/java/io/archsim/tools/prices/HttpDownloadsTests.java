package io.archsim.tools.prices;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/**
 * Downloads from a server on this machine that serves a price list at {@code /list.csv},
 * refuses {@code /denied} with 403, and at {@code /stalls} sends the list's headers and
 * first byte, then nothing until the test ends.
 */
class HttpDownloadsTests {

	private static final byte[] LIST = "SKU,a\n".getBytes(StandardCharsets.UTF_8);

	private static final int FORBIDDEN = 403;

	private final AtomicInteger requests = new AtomicInteger();

	private final AtomicReference<String> userAgent = new AtomicReference<>("");

	private final HttpDownloads downloads = new HttpDownloads();

	private final HttpServer server = startServer();

	private final CountDownLatch ended = new CountDownLatch(1);

	@BeforeEach
	void serve() {
		this.server.createContext("/list.csv", this::list);
		this.server.createContext("/stalls", this::stall);
		this.server.createContext("/denied", (exchange) -> {
			exchange.sendResponseHeaders(FORBIDDEN, -1);
			exchange.close();
		});
		this.server.start();
	}

	@AfterEach
	void stop() {
		this.ended.countDown();
		this.server.stop(0);
	}

	@Test
	void file_notCachedYet_isDownloadedOnceAndKept(@TempDir Path folder) throws IOException {
		Path cache = folder.resolve("offers").resolve("AmazonEC2-x-1.csv");

		this.downloads.file(address("/list.csv"), cache);
		Path second = this.downloads.file(address("/list.csv"), cache);

		assertThat(second).isEqualTo(cache).hasBinaryContent(LIST);
		assertThat(this.requests).hasValue(1);
		assertThat(cache.resolveSibling("AmazonEC2-x-1.csv.part")).doesNotExist();
	}

	@Test
	void bytes_aPage_isAskedForAsThePriceRefresh() throws IOException {
		byte[] page = this.downloads.bytes(address("/list.csv"));

		assertThat(page).isEqualTo(LIST);
		assertThat(this.userAgent).hasValue("archsim-price-refresh");
	}

	@Test
	void bytes_anErrorStatus_isRefusedWithoutTheQuery() {
		assertThatIOException().isThrownBy(() -> this.downloads.bytes(address("/denied?key=secret")))
			.withMessageContaining("403")
			.withMessageNotContaining("secret");
	}

	@Test
	void bytes_aBodyThatStalls_failsAtItsDeadline() {
		HttpDownloads impatient = new HttpDownloads(Duration.ofMillis(500), Duration.ofMillis(500));

		assertThatIOException().isThrownBy(() -> impatient.bytes(address("/stalls?key=secret")))
			.withMessageStartingWith("no whole answer within PT0.5S from http://127.0.0.1:")
			.withMessageNotContaining("secret");
	}

	private URI address(String path) {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + path);
	}

	private void list(HttpExchange exchange) throws IOException {
		this.requests.incrementAndGet();
		this.userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
		exchange.sendResponseHeaders(200, LIST.length);
		try (OutputStream body = exchange.getResponseBody()) {
			body.write(LIST);
		}
	}

	// the headers and the body's first byte, then nothing until the test ends
	private void stall(HttpExchange exchange) throws IOException {
		exchange.sendResponseHeaders(200, LIST.length);
		OutputStream body = exchange.getResponseBody();
		body.write(LIST, 0, 1);
		body.flush();
		try {
			this.ended.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static HttpServer startServer() {
		try {
			return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
