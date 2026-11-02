package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * A client of one API server, like a browser or the CLI: it keeps the cookies the server
 * sets (all of them: the hosted profile's {@code __Host-} cookies are {@code Secure}, and
 * tests talk plain HTTP to 127.0.0.1) and sends default headers with every request. It
 * never follows redirects; {@link #open} does, one step at a time.
 *
 * <p>
 * Paths are relative to {@value #API}: {@code client.get("/projects")}.
 */
public final class ApiClient {

	/** The API's root path; every route lives under it. */
	public static final String API = "/api/v1";

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private static final Duration TIMEOUT = Duration.ofMinutes(3);

	private final URI base;

	private final @Nullable String publicUrl;

	private final Map<String, String> headers = new LinkedHashMap<>();

	private final Map<String, String> cookies = new LinkedHashMap<>();

	/**
	 * @param base the server, e.g. {@code http://127.0.0.1:8101}
	 * @param publicUrl the address the server believes it has (its redirects name it), or
	 * null
	 */
	public ApiClient(URI base, @Nullable String publicUrl) {
		this.base = base;
		this.publicUrl = publicUrl;
	}

	public URI baseUrl() {
		return base;
	}

	/**
	 * Sets a header sent with every request, or removes it when {@code value} is null.
	 */
	public ApiClient header(String name, @Nullable String value) {
		headers.keySet().removeIf(n -> n.equalsIgnoreCase(name));
		if (value != null) {
			headers.put(name, value);
		}
		return this;
	}

	/** A default header's value, or null. */
	public @Nullable String header(String name) {
		return headers.entrySet()
			.stream()
			.filter(e -> e.getKey().equalsIgnoreCase(name))
			.map(Map.Entry::getValue)
			.findFirst()
			.orElse(null);
	}

	/**
	 * The cookies this client holds, by name; change them to play a browser that kept
	 * one.
	 */
	public Map<String, String> cookies() {
		return cookies;
	}

	/** A cookie's value, or null. */
	public @Nullable String cookie(String name) {
		return cookies.get(name);
	}

	/** {@code path} with a query string; null values are left out. */
	public static String query(String path, @Nullable Object... namesAndValues) {
		if (namesAndValues.length % 2 != 0) {
			throw new IllegalArgumentException("names and values come in pairs");
		}
		StringJoiner q = new StringJoiner("&");
		for (int i = 0; i < namesAndValues.length; i += 2) {
			Object value = namesAndValues[i + 1];
			if (value != null) {
				q.add(encode(String.valueOf(namesAndValues[i])) + "=" + encode(String.valueOf(value)));
			}
		}
		String qs = q.toString();
		return qs.isEmpty() ? path : path + (path.contains("?") ? "&" : "?") + qs;
	}

	private static String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	public ApiResponse get(String path) {
		return send("GET", path, null, Map.of());
	}

	public ApiResponse get(String path, Map<String, String> extraHeaders) {
		return send("GET", path, null, extraHeaders);
	}

	/** A POST without a body. */
	public ApiResponse post(String path) {
		return send("POST", path, null, Map.of());
	}

	/** A POST of {@code json} (a Jackson node, a map, a list, a string...). */
	public ApiResponse post(String path, Object json) {
		return send("POST", path, json, Map.of());
	}

	public ApiResponse post(String path, @Nullable Object json, Map<String, String> extraHeaders) {
		return send("POST", path, json, extraHeaders);
	}

	public ApiResponse put(String path, Object json) {
		return send("PUT", path, json, Map.of());
	}

	public ApiResponse put(String path, Object json, Map<String, String> extraHeaders) {
		return send("PUT", path, json, extraHeaders);
	}

	public ApiResponse patch(String path, Object json) {
		return send("PATCH", path, json, Map.of());
	}

	public ApiResponse patch(String path, Object json, Map<String, String> extraHeaders) {
		return send("PATCH", path, json, extraHeaders);
	}

	public ApiResponse delete(String path) {
		return send("DELETE", path, null, Map.of());
	}

	public ApiResponse delete(String path, Map<String, String> extraHeaders) {
		return send("DELETE", path, null, extraHeaders);
	}

	/** A request with {@code json} as its body (none when null). */
	public ApiResponse send(String method, String path, @Nullable Object json, Map<String, String> extraHeaders) {
		byte[] body = json == null ? null : Json.write(json).getBytes(StandardCharsets.UTF_8);
		return exchange(method, base.resolve(API + path), body, extraHeaders);
	}

	/** A request with a body that is not JSON, e.g. a form or a broken document. */
	public ApiResponse sendRaw(String method, String path, String contentType, byte[] body,
			Map<String, String> extraHeaders) {
		Map<String, String> h = new LinkedHashMap<>(extraHeaders);
		h.put("Content-Type", contentType);
		return exchange(method, base.resolve(API + path), body, h);
	}

	/**
	 * GETs where a redirect pointed: an absolute path, or a URL on this server or its
	 * public address. Fails for any other site.
	 */
	public ApiResponse open(String location) {
		String path = location;
		if (publicUrl != null && path.startsWith(publicUrl)) {
			path = path.substring(publicUrl.length());
		}
		else if (path.startsWith(base.toString())) {
			path = path.substring(base.toString().length());
		}
		if (!path.startsWith("/") || path.startsWith("//")) {
			throw new AssertionError("not a location on this server: " + location);
		}
		return exchange("GET", base.resolve(path), null, Map.of());
	}

	private ApiResponse exchange(String method, URI uri, byte @Nullable [] body, Map<String, String> extraHeaders) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
		Map<String, String> all = new LinkedHashMap<>(headers);
		extraHeaders.forEach((name, value) -> {
			all.keySet().removeIf(n -> n.equalsIgnoreCase(name));
			all.put(name, value);
		});
		if (body != null && all.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
			all.put("Content-Type", "application/json");
		}
		all.forEach(request::header);
		if (!cookies.isEmpty()) {
			StringJoiner cookie = new StringJoiner("; ");
			cookies.forEach((name, value) -> cookie.add(name + "=" + value));
			request.header("Cookie", cookie.toString());
		}
		try {
			var response = HTTP.send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
			remember(response.headers().allValues("set-cookie"));
			String shown = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
			return new ApiResponse(method, shown, response.statusCode(), response.headers(), response.body());
		}
		catch (IOException e) {
			throw new UncheckedIOException(method + " " + uri + " failed", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(method + " " + uri + " was interrupted", e);
		}
	}

	/**
	 * Keeps or forgets cookies as a browser would (a past expiry or a zero Max-Age
	 * deletes).
	 */
	private void remember(List<String> setCookies) {
		for (String setCookie : setCookies) {
			String[] parts = setCookie.split(";", -1);
			String[] nameValue = parts[0].split("=", 2);
			String name = nameValue[0].strip();
			String value = nameValue.length > 1 ? nameValue[1].strip() : "";
			boolean expired = false;
			for (int i = 1; i < parts.length; i++) {
				String[] attribute = parts[i].split("=", 2);
				String key = attribute[0].strip();
				String v = attribute.length > 1 ? attribute[1].strip() : "";
				if (key.equalsIgnoreCase("max-age")) {
					expired |= v.startsWith("-") || v.equals("0");
				}
				else if (key.equalsIgnoreCase("expires")) {
					try {
						expired |= ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
							.isBefore(ZonedDateTime.now(ZoneOffset.UTC));
					}
					catch (DateTimeParseException ignored) {
						// a date nobody can read: keep the cookie, as browsers do
					}
				}
			}
			if (expired) {
				cookies.remove(name);
			}
			else {
				cookies.put(name, value);
			}
		}
	}

}
