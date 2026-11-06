package io.archsim.app.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.limits.WorkspaceLimits.RunLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.issue.Issue;

/**
 * The Python engine service (ADR-0036), which runs the engine's synchronous calls until
 * stage 3 of the port moves each one to Java: calibration, the last. Its answers are
 * passed on as it wrote them.
 */
public final class EngineService {

	private static final Logger LOGGER = LoggerFactory.getLogger(EngineService.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** What the API answers when the service takes too long. */
	private static final String TOO_SLOW = "The simulation engine took too long to answer: "
			+ "calibrate with replay off, or over a shorter range";

	private final RestClient http;

	/**
	 * A client of the service at the base URL of {@code http}.
	 * @param http a client whose base URL is the service's
	 */
	public EngineService(RestClient http) {
		this.http = http;
	}

	/**
	 * A design calibrated from telemetry. The engine service calls the Prometheus server
	 * the request names, if the policy sent with it allows.
	 * @param request what {@code POST /calibrate} takes
	 * @param policy which Prometheus servers it may call, and how
	 * @param replay the limits of the replay that checks the result
	 * @return the calibrated design, the changes, notes and the calibration record
	 * @throws EngineRefusalException when the design, the mapping or the telemetry cannot
	 * be used
	 * @throws ApiException {@code 503} when the service cannot be reached or answers too
	 * late
	 */
	public JsonText calibrate(ObjectNode request, OutboundPolicy policy, RunLimits replay) {
		ObjectNode body = request.deepCopy();
		// the policy last, so that nothing the request carries stands in for it
		body.put("outbound", policy.mode().json());
		ArrayNode hosts = body.putArray("allowedHosts");
		policy.allowedHosts().forEach(hosts::add);
		body.put("timeoutS", policy.timeoutSeconds());
		body.put("maxBytes", policy.maxBytes());
		body.put("maxRequests", replay.maxRequests());
		body.put("maxWallSeconds", replay.maxWallSeconds());
		return call("/calibrate", body);
	}

	/**
	 * Calls the engine service.
	 * @param path the endpoint
	 * @param request its body
	 * @return the answer, as the engine wrote it
	 * @throws EngineRefusalException when the engine refuses
	 * @throws ApiException {@code 503} when the service cannot be reached or answers too
	 * late
	 */
	private JsonText call(String path, ObjectNode request) {
		try {
			return postOnceMoreIfDropped(path, request);
		}
		catch (ResourceAccessException ex) {
			if (ex.getCause() instanceof HttpTimeoutException) {
				LOGGER.warn("The engine service did not answer {} in time", path, ex);
				throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, TOO_SLOW, List.of());
			}
			LOGGER.warn("The engine service cannot be reached at {}", path, ex);
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"The simulation engine is not available right now: try again in a moment", List.of());
		}
	}

	/**
	 * Posts a request, and once more when its connection drops before an answer. The
	 * service closes a connection left idle for 5 s (uvicorn's keep-alive), and the
	 * client may reuse it just then. Every call is a stateless POST, so sending it again
	 * is safe.
	 * @param path the endpoint
	 * @param request its body
	 * @return the answer, as the engine wrote it
	 * @throws ResourceAccessException when the service cannot be reached, drops the
	 * connection again, answers too late, or is interrupted
	 */
	private JsonText postOnceMoreIfDropped(String path, ObjectNode request) {
		try {
			return post(path, request);
		}
		catch (ResourceAccessException ex) {
			if (!droppedConnection(ex)) {
				throw ex;
			}
			LOGGER.debug("The engine service dropped the connection for {}: sending it once more", path, ex);
			return post(path, request);
		}
	}

	/**
	 * Whether a call failed because its connection dropped before an answer. Not when it
	 * timed out, nor when the service refused the connection (a reused connection is open
	 * already, so it is never refused), nor when the calling thread was interrupted.
	 * @param ex how the call failed
	 * @return whether the connection dropped
	 */
	private static boolean droppedConnection(ResourceAccessException ex) {
		Throwable cause = ex.getCause();
		if (cause instanceof HttpTimeoutException || cause instanceof ConnectException) {
			return false;
		}
		return !Thread.currentThread().isInterrupted();
	}

	private JsonText post(String path, ObjectNode request) {
		String answer = this.http.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_JSON)
			.body(request)
			.retrieve()
			.onStatus((status) -> status.isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT),
					(sent, response) -> refuse(response))
			.body(String.class);
		return new JsonText(Objects.requireNonNull(answer, "the engine service answered nothing"));
	}

	/**
	 * Reads a {@code 422} answer and throws it.
	 * @param response the engine service's answer
	 * @throws EngineRefusalException always, with the answer's words and issues
	 */
	private static void refuse(ClientHttpResponse response) throws IOException {
		try (InputStream body = response.getBody()) {
			JsonNode refusal = JSON.readTree(body);
			List<Issue> issues = refusal.path("issues")
				.valueStream()
				.map((issue) -> RecordCodec.read(issue, Issue.class))
				.toList();
			String message = refusal.path("message").asString("");
			throw switch (refusal.path("kind").asString("")) {
				case "unreadable" -> EngineRefusalException.unreadable(message);
				default -> EngineRefusalException.invalidDesign(message, issues);
			};
		}
	}

}
