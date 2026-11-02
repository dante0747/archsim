package io.archsim.apitests;

import java.net.http.HttpHeaders;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** One answer of the API: status, headers and body, with the body read as JSON on demand. */
public final class ApiResponse {
    private final String method;
    private final String path;
    private final int status;
    private final HttpHeaders headers;
    private final String text;
    private @Nullable JsonNode json;

    ApiResponse(String method, String path, int status, HttpHeaders headers, String text) {
        this.method = method;
        this.path = path;
        this.status = status;
        this.headers = headers;
        this.text = text;
    }

    public int status() {
        return status;
    }

    /** The body as text. */
    public String text() {
        return text;
    }

    /** The body as JSON; fails the test, showing the body, when it is not JSON. */
    public JsonNode json() {
        JsonNode parsed = json;
        if (parsed == null) {
            try {
                parsed = Json.parse(text);
            } catch (JacksonException e) {
                throw new AssertionError(this + " is not JSON: " + text, e);
            }
            json = parsed;
        }
        return parsed;
    }

    /** {@code detail.message} of an error answer, or "" when there is none. */
    public String message() {
        return json().path("detail").path("message").asString("");
    }

    /** The first value of a header (names are case-insensitive), or null. */
    public @Nullable String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    /** Every value of a header, e.g. each {@code Set-Cookie}. */
    public List<String> headers(String name) {
        return headers.allValues(name);
    }

    /** The {@code Set-Cookie} headers. */
    public List<String> setCookies() {
        return headers("set-cookie");
    }

    /** The {@code Set-Cookie} header that sets {@code name}, or null. */
    public @Nullable String setCookie(String name) {
        return setCookies().stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .orElse(null);
    }

    /** Where a redirect points. */
    public String location() {
        String location = header("location");
        if (location == null) {
            throw new AssertionError(this + " has no Location header: " + text);
        }
        return location;
    }

    @Override
    public String toString() {
        return method + " " + path + " -> " + status;
    }
}
