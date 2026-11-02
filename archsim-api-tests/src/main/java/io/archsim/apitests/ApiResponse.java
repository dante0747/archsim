package io.archsim.apitests;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * One answer of the API: status, headers and body, with the body read as JSON on demand.
 */
public final class ApiResponse {

	private final String request;

	private final int status;

	private final HttpHeaders headers;

	private final String text;

	private @Nullable JsonNode json;

	/**
	 * Wraps the answer to one request.
	 * @param request the request as tests show it: its method and path, e.g.
	 * {@code GET /api/v1/projects}
	 * @param response the server's answer
	 */
	ApiResponse(String request, HttpResponse<String> response) {
		this.request = request;
		this.status = response.statusCode();
		this.headers = response.headers();
		this.text = response.body();
	}

	public int status() {
		return this.status;
	}

	/**
	 * Returns the body as text.
	 * @return the body, as the server sent it
	 */
	public String text() {
		return this.text;
	}

	/**
	 * Returns the body as JSON; fails the test, showing the body, when it is not JSON.
	 * @return the body, parsed once and kept
	 */
	public JsonNode json() {
		JsonNode parsed = this.json;
		if (parsed == null) {
			try {
				parsed = Json.parse(this.text);
			}
			catch (JacksonException ex) {
				throw new AssertionError(this + " is not JSON: " + this.text, ex);
			}
			this.json = parsed;
		}
		return parsed;
	}

	/**
	 * Returns the message of an error answer.
	 * @return the body's {@code detail.message}, or "" when there is none
	 */
	public String message() {
		return json().path("detail").path("message").asString("");
	}

	/**
	 * Returns the first value of a header.
	 * @param name the header's name, in any case
	 * @return the header's first value, or null when the answer has no such header
	 */
	public @Nullable String header(String name) {
		return this.headers.firstValue(name).orElse(null);
	}

	/**
	 * Returns every value of a header, e.g. each {@code Set-Cookie}.
	 * @param name the header's name, in any case
	 * @return the header's values, in the order the server sent them
	 */
	public List<String> headers(String name) {
		return this.headers.allValues(name);
	}

	/**
	 * Returns the {@code Set-Cookie} headers.
	 * @return each {@code Set-Cookie} header, in the order the server sent them
	 */
	public List<String> setCookies() {
		return headers("set-cookie");
	}

	/**
	 * Returns the {@code Set-Cookie} header that sets a cookie.
	 * @param name the cookie's name
	 * @return the first header that sets {@code name}, or null when none does
	 */
	public @Nullable String setCookie(String name) {
		return setCookies().stream().filter((setCookie) -> setCookie.startsWith(name + "=")).findFirst().orElse(null);
	}

	/**
	 * Returns where a redirect points; fails the test when the answer has no
	 * {@code Location}.
	 * @return the {@code Location} header
	 */
	public String location() {
		String location = header("location");
		if (location == null) {
			throw new AssertionError(this + " has no Location header: " + this.text);
		}
		return location;
	}

	/**
	 * Fails the test, showing the answer, unless it has the expected status.
	 * @param expected the status the test expects
	 * @return this answer
	 */
	ApiResponse expectStatus(int expected) {
		if (this.status != expected) {
			throw new AssertionError(this + ": " + this.text);
		}
		return this;
	}

	@Override
	public String toString() {
		return this.request + " -> " + this.status;
	}

}
