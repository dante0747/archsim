package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
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

	private static final String CONTENT_TYPE = "Content-Type";

	private final URI base;

	private final @Nullable String publicUrl;

	private final Map<String, String> headers = new LinkedHashMap<>();

	private final Map<String, String> cookies = new LinkedHashMap<>();

	/**
	 * Creates a client of one server, holding no cookies and sending no default headers.
	 * @param base the server, e.g. {@code http://127.0.0.1:8101}
	 * @param publicUrl the address the server believes it has (its redirects name it), or
	 * null
	 */
	public ApiClient(URI base, @Nullable String publicUrl) {
		this.base = base;
		this.publicUrl = publicUrl;
	}

	public URI baseUrl() {
		return this.base;
	}

	/**
	 * Sets a header sent with every request, or removes it when {@code value} is null.
	 * @param name the header's name, in any case
	 * @param value the header's value, or null to stop sending it
	 * @return this client
	 */
	public ApiClient header(String name, @Nullable String value) {
		removeHeader(this.headers, name);
		if (value != null) {
			this.headers.put(name, value);
		}
		return this;
	}

	/**
	 * Returns a header this client sends with every request.
	 * @param name the header's name, in any case
	 * @return the header's value, or null when the client does not send it
	 */
	public @Nullable String header(String name) {
		return this.headers.entrySet()
			.stream()
			.filter((header) -> header.getKey().equalsIgnoreCase(name))
			.map(Map.Entry::getValue)
			.findFirst()
			.orElse(null);
	}

	/**
	 * Returns the cookies this client holds; change them to play a browser that kept one.
	 * @return the cookies' values by name, which the client sends and updates
	 */
	public Map<String, String> cookies() {
		return this.cookies;
	}

	/**
	 * Returns a cookie this client holds.
	 * @param name the cookie's name
	 * @return the cookie's value, or null when the client holds no such cookie
	 */
	public @Nullable String cookie(String name) {
		return this.cookies.get(name);
	}

	/**
	 * Adds a query string to a path, leaving out the parameters whose value is null.
	 * @param path the path, which may have a query string already
	 * @param namesAndValues each parameter's name followed by its value
	 * @return {@code path} with the parameters, encoded, in its query string
	 */
	public static String query(String path, @Nullable Object... namesAndValues) {
		if (namesAndValues.length % 2 != 0) {
			throw new IllegalArgumentException("names and values come in pairs");
		}
		StringJoiner parameters = new StringJoiner("&");
		for (int i = 0; i < namesAndValues.length; i += 2) {
			Object value = namesAndValues[i + 1];
			if (value != null) {
				parameters.add(encode(String.valueOf(namesAndValues[i])) + "=" + encode(String.valueOf(value)));
			}
		}
		String queryString = parameters.toString();
		if (queryString.isEmpty()) {
			return path;
		}
		String separator = path.contains("?") ? "&" : "?";
		return path + separator + queryString;
	}

	public ApiResponse get(String path) {
		return send("GET", path, null, Map.of());
	}

	public ApiResponse get(String path, Map<String, String> extraHeaders) {
		return send("GET", path, null, extraHeaders);
	}

	/**
	 * Sends a POST without a body.
	 * @param path the path under {@value #API}, with any query string
	 * @return the server's answer
	 */
	public ApiResponse post(String path) {
		return send("POST", path, null, Map.of());
	}

	/**
	 * Sends a POST of {@code json}.
	 * @param path the path under {@value #API}, with any query string
	 * @param json the body: a Jackson node, a map, a list, a string...
	 * @return the server's answer
	 */
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

	/**
	 * Sends a request with {@code json} as its body (none when null).
	 * @param method the HTTP method
	 * @param path the path under {@value #API}, with any query string
	 * @param json the body: a Jackson node, a map, a list, a string..., or null for none
	 * @param extraHeaders headers for this request only, over the default ones
	 * @return the server's answer
	 */
	public ApiResponse send(String method, String path, @Nullable Object json, Map<String, String> extraHeaders) {
		byte[] body = (json != null) ? Json.write(json).getBytes(StandardCharsets.UTF_8) : null;
		return exchange(method, this.base.resolve(API + path), body, extraHeaders);
	}

	/**
	 * Sends a request with a body that is not JSON, e.g. a form or a broken document. The
	 * body goes as {@code application/json} unless {@code extraHeaders} name another
	 * {@code Content-Type}.
	 * @param method the HTTP method
	 * @param path the path under {@value #API}, with any query string
	 * @param body the body, sent as it is
	 * @param extraHeaders headers for this request only, over the default ones
	 * @return the server's answer
	 */
	public ApiResponse sendRaw(String method, String path, byte[] body, Map<String, String> extraHeaders) {
		return exchange(method, this.base.resolve(API + path), body, extraHeaders);
	}

	/**
	 * GETs where a redirect pointed: an absolute path, or a URL on this server or its
	 * public address. Fails for any other site.
	 * @param location the redirect's {@code Location}
	 * @return the server's answer
	 */
	public ApiResponse open(String location) {
		String path = location;
		if (this.publicUrl != null && path.startsWith(this.publicUrl)) {
			path = path.substring(this.publicUrl.length());
		}
		else if (path.startsWith(this.base.toString())) {
			path = path.substring(this.base.toString().length());
		}
		if (!path.startsWith("/") || path.startsWith("//")) {
			throw new AssertionError("not a location on this server: " + location);
		}
		return exchange("GET", this.base.resolve(path), null, Map.of());
	}

	private ApiResponse exchange(String method, URI uri, byte @Nullable [] body, Map<String, String> extraHeaders) {
		HttpRequest request = request(method, uri, body, extraHeaders);
		try {
			HttpResponse<String> response = HTTP.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
			remember(response.headers().allValues("set-cookie"));
			return new ApiResponse(method + " " + pathAndQuery(uri), response);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(method + " " + uri + " failed", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(method + " " + uri + " was interrupted", ex);
		}
	}

	private HttpRequest request(String method, URI uri, byte @Nullable [] body, Map<String, String> extraHeaders) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.method(method, (body != null) ? BodyPublishers.ofByteArray(body) : BodyPublishers.noBody());
		requestHeaders(body, extraHeaders).forEach(request::header);
		if (!this.cookies.isEmpty()) {
			request.header("Cookie", cookieHeader());
		}
		return request.build();
	}

	/**
	 * Merges the headers of one request.
	 * @param body the request's body, or null for none
	 * @param extraHeaders the request's own headers
	 * @return the default headers, overridden by the request's own, and a JSON content
	 * type for a body when they name none
	 */
	private Map<String, String> requestHeaders(byte @Nullable [] body, Map<String, String> extraHeaders) {
		Map<String, String> all = new LinkedHashMap<>(this.headers);
		extraHeaders.forEach((name, value) -> {
			removeHeader(all, name);
			all.put(name, value);
		});
		if (body != null && all.keySet().stream().noneMatch(CONTENT_TYPE::equalsIgnoreCase)) {
			all.put(CONTENT_TYPE, "application/json");
		}
		return all;
	}

	private String cookieHeader() {
		StringJoiner cookie = new StringJoiner("; ");
		this.cookies.forEach((name, value) -> cookie.add(name + "=" + value));
		return cookie.toString();
	}

	/**
	 * Keeps or forgets cookies as a browser would (a past expiry or a zero Max-Age
	 * deletes).
	 * @param setCookies the {@code Set-Cookie} headers of an answer
	 */
	private void remember(List<String> setCookies) {
		for (String setCookie : setCookies) {
			String[] parts = setCookie.split(";", -1);
			String[] nameAndValue = parts[0].split("=", 2);
			String name = nameAndValue[0].strip();
			if (deletes(parts)) {
				this.cookies.remove(name);
			}
			else {
				this.cookies.put(name, valuePart(nameAndValue));
			}
		}
	}

	/**
	 * Tells whether a {@code Set-Cookie} header deletes its cookie.
	 * @param parts the header split at its semicolons: the cookie, then its attributes
	 * @return whether an attribute deletes the cookie
	 */
	private static boolean deletes(String[] parts) {
		boolean expired = false;
		for (int i = 1; i < parts.length; i++) {
			String[] attribute = parts[i].split("=", 2);
			expired |= expires(attribute[0].strip(), valuePart(attribute));
		}
		return expired;
	}

	/**
	 * Tells whether one attribute of a {@code Set-Cookie} header deletes its cookie.
	 * @param attribute the attribute's name
	 * @param value the attribute's value, or "" when it has none
	 * @return whether it is a negative or zero Max-Age, or an Expires in the past
	 */
	private static boolean expires(String attribute, String value) {
		if ("max-age".equalsIgnoreCase(attribute)) {
			return value.startsWith("-") || "0".equals(value);
		}
		if ("expires".equalsIgnoreCase(attribute)) {
			return isPast(value);
		}
		return false;
	}

	private static boolean isPast(String httpDate) {
		try {
			return ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME)
				.isBefore(ZonedDateTime.now(ZoneOffset.UTC));
		}
		catch (DateTimeParseException ex) {
			// a date nobody can read: keep the cookie, as browsers do
			return false;
		}
	}

	/**
	 * The value of a {@code name=value} pair split at its first {@code =}.
	 * @param nameAndValue the name, and the value if there is one
	 * @return the value, stripped, or "" when there is none
	 */
	private static String valuePart(String[] nameAndValue) {
		return (nameAndValue.length > 1) ? nameAndValue[1].strip() : "";
	}

	private static String pathAndQuery(URI uri) {
		String query = uri.getRawQuery();
		return (query != null) ? uri.getRawPath() + "?" + query : uri.getRawPath();
	}

	private static void removeHeader(Map<String, String> requestHeaders, String name) {
		requestHeaders.keySet().removeIf(name::equalsIgnoreCase);
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

}
