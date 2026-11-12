package io.archsim.app.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a refusal outside Spring MVC, from a filter, as {@code ApiExceptionHandler}
 * answers one inside it: its status, its headers and the API's error body.
 */
public final class ErrorResponses {

	private final JsonMapper json;

	/**
	 * Writes refusals with the application's JSON mapper.
	 * @param json the mapper the API writes its bodies with
	 */
	public ErrorResponses(JsonMapper json) {
		this.json = json;
	}

	/**
	 * Answers a request with a refusal.
	 * @param response the response, not yet committed
	 * @param refusal the status, message and headers to answer with
	 * @throws IOException when the body cannot be written
	 */
	public void write(HttpServletResponse response, ApiException refusal) throws IOException {
		response.setStatus(refusal.status().value());
		refusal.headers().forEach((name, values) -> values.forEach((value) -> response.addHeader(name, value)));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		this.json.writeValue(response.getOutputStream(), ErrorBody.of(refusal));
	}

}
