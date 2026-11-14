package io.archsim.tools.prices;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The providers' public price endpoints over HTTPS, asked as
 * {@code archsim-price-refresh}. A cached download is streamed to a {@code .part} file
 * beside the cache and renamed once complete, so that an interrupted one is never taken
 * for a price list. An error names the address without its query, which can hold an API
 * key.
 *
 * <p>
 * The scripts' urllib gave up when a read waited two minutes. HttpClient's own timeout
 * ends with the answer's headers, so a body that stalls would wait for ever: each
 * download has a deadline for the whole answer instead, two minutes for a page and half
 * an hour for a file (AWS's EC2 offer file is several hundred MB).
 */
final class HttpDownloads implements PriceDownloads {

	private static final String USER_AGENT = "archsim-price-refresh";

	/**
	 * How long a connection and an answer's headers may take, as the scripts' timeout.
	 */
	private static final Duration TIMEOUT = Duration.ofMinutes(2);

	private static final Duration FILE_DEADLINE = Duration.ofMinutes(30);

	private static final int SUCCESS = 2;

	private static final int STATUS_CLASS = 100;

	private final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private final Duration pageDeadline;

	private final Duration fileDeadline;

	/**
	 * Downloads with the standard deadlines.
	 */
	HttpDownloads() {
		this(TIMEOUT, FILE_DEADLINE);
	}

	/**
	 * Downloads with deadlines of their own.
	 * @param pageDeadline how long a page's whole answer may take
	 * @param fileDeadline how long a file's whole answer may take
	 */
	HttpDownloads(Duration pageDeadline, Duration fileDeadline) {
		this.pageDeadline = pageDeadline;
		this.fileDeadline = fileDeadline;
	}

	@Override
	public byte[] bytes(URI address) throws IOException {
		return send(address, BodyHandlers.ofByteArray(), this.pageDeadline).body();
	}

	@Override
	public Path file(URI address, Path cache) throws IOException {
		if (Files.exists(cache) && Files.size(cache) > 0) {
			return cache;
		}
		Files.createDirectories(cache.toAbsolutePath().getParent());
		Path part = cache.resolveSibling(cache.getFileName() + ".part");
		try {
			send(address, BodyHandlers.ofFile(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING), this.fileDeadline);
		}
		catch (IOException ex) {
			deleteAfter(ex, part);
			throw ex;
		}
		Files.move(part, cache, StandardCopyOption.REPLACE_EXISTING);
		return cache;
	}

	// a failed download's part file; one the cancelled download still holds open (Windows
	// refuses the delete) is left, and the download's own failure is the one reported
	private static void deleteAfter(IOException failure, Path part) {
		try {
			Files.deleteIfExists(part);
		}
		catch (IOException ex) {
			failure.addSuppressed(ex);
		}
	}

	private <T> HttpResponse<T> send(URI address, BodyHandler<T> body, Duration deadline) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(address).header("User-Agent", USER_AGENT).timeout(TIMEOUT).build();
		CompletableFuture<HttpResponse<T>> answer = this.client.sendAsync(request, body);
		try {
			HttpResponse<T> response = answer.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
			if (response.statusCode() / STATUS_CLASS != SUCCESS) {
				throw new IOException("HTTP " + response.statusCode() + " from " + where(address));
			}
			return response;
		}
		catch (ExecutionException ex) {
			throw (ex.getCause() instanceof IOException cause) ? cause : new IOException(ex.getCause());
		}
		catch (TimeoutException ex) {
			answer.cancel(true);
			throw new HttpTimeoutException("no whole answer within " + deadline + " from " + where(address));
		}
		catch (InterruptedException ex) {
			answer.cancel(true);
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("interrupted fetching " + where(address));
		}
	}

	// the address without its query, which can hold an API key
	private static String where(URI address) {
		return address.getScheme() + "://" + address.getRawAuthority() + address.getRawPath();
	}

}
