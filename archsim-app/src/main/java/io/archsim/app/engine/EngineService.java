package io.archsim.app.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
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

import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.issue.Issue;

/**
 * The Python engine service (ADR-0036), which runs the engine's synchronous calls until
 * stage 3 of the port moves each one to Java. Its answers are passed on as it wrote them.
 */
public final class EngineService {

	private static final Logger LOGGER = LoggerFactory.getLogger(EngineService.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient http;

	/**
	 * A client of the service at the base URL of {@code http}.
	 * @param http a client whose base URL is the service's
	 */
	public EngineService(RestClient http) {
		this.http = http;
	}

	/**
	 * The auto sizes, cost and design review of a design or one of its scenarios
	 * ({@code analyze_document}).
	 * @param document the design, as the request carries it
	 * @param scenarioId the scenario to analyze, or {@code null} for the design itself
	 * @return the analysis, as the engine wrote it
	 * @throws EngineRefusalException when the engine cannot read the design
	 * @throws ApiException {@code 503} when the service cannot be reached or does not
	 * answer in time
	 */
	public JsonText analyze(JsonNode document, @Nullable String scenarioId) {
		return call("/analyze", design(document, scenarioId));
	}

	/**
	 * The validation of one of a design's scenarios, resolved first as a run resolves it
	 * (auto sizes, pinned prices, overrides). A design that cannot be read or resolved is
	 * answered as invalid, not refused.
	 * @param document the design, as the request carries it
	 * @param scenarioId the scenario to validate
	 * @return the validation, as the engine wrote it
	 * @throws ApiException {@code 503} when the service cannot be reached or does not
	 * answer in time
	 */
	public JsonText validateScenario(JsonNode document, String scenarioId) {
		return call("/validate", design(document, scenarioId));
	}

	/**
	 * Prepares a run to be queued ({@code prepare_run}): the design to store with it, its
	 * scenario and seed, and whether a quick check can answer it.
	 * @param request the design, scenario, limits and seed
	 * @return what the engine prepared
	 * @throws EngineRefusalException when the engine cannot simulate the design, or the
	 * run is over its limits
	 * @throws ApiException {@code 503} when the service cannot be reached or does not
	 * answer in time
	 */
	public PreparedRun prepare(PrepareRequest request) {
		ObjectNode body = design(request.document(), request.scenarioId());
		body.put("maxRequests", request.maxRequests());
		body.put("maxWallSeconds", request.maxWallSeconds());
		body.put("seed", request.seed());
		return RecordCodec.read(JSON.readTree(call("/prepare", body).json()), PreparedRun.class);
	}

	/**
	 * Compares two finished runs' reports ({@code compare_reports}).
	 * @param first the first run's report
	 * @param second the second run's report
	 * @param firstLabel what to call the first
	 * @param secondLabel what to call the second
	 * @return the comparison: KPI deltas, input differences and explanations
	 * @throws ApiException {@code 503} when the service cannot be reached or does not
	 * answer in time
	 */
	public ObjectNode compare(JsonNode first, JsonNode second, String firstLabel, String secondLabel) {
		ObjectNode body = JSON.createObjectNode();
		body.set("a", first);
		body.set("b", second);
		body.put("labelA", firstLabel);
		body.put("labelB", secondLabel);
		return (ObjectNode) JSON.readTree(call("/compare", body).json());
	}

	/**
	 * The summary of a seed group's runs against the objectives they ran with
	 * ({@code summarize_seeds}).
	 * @param runs each run's {@code seed} and, if it succeeded, {@code summary}
	 * @param objectives the design's objectives
	 * @return the summary: the verdict split, percentiles and ranges
	 * @throws ApiException {@code 503} when the service cannot be reached or does not
	 * answer in time
	 */
	public JsonNode summarizeSeeds(ArrayNode runs, JsonNode objectives) {
		ObjectNode body = JSON.createObjectNode();
		body.set("runs", runs);
		body.set("objectives", objectives);
		return JSON.readTree(call("/summarize-seeds", body).json());
	}

	private static ObjectNode design(JsonNode document, @Nullable String scenarioId) {
		ObjectNode request = JSON.createObjectNode();
		request.set("document", document);
		request.put("scenarioId", scenarioId);
		return request;
	}

	private JsonText call(String path, ObjectNode request) {
		try {
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
		catch (ResourceAccessException ex) {
			if (ex.getCause() instanceof HttpTimeoutException) {
				LOGGER.warn("The engine service did not answer {} in time", path, ex);
				throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
						"The simulation engine took too long to answer: a smaller design may be quicker", List.of());
			}
			LOGGER.warn("The engine service cannot be reached at {}", path, ex);
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"The simulation engine is not available right now: try again in a moment", List.of());
		}
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
			throw refusal.path("limit").asBoolean(false) ? EngineRefusalException.runOverLimits(message)
					: EngineRefusalException.invalidDesign(message, issues);
		}
	}

}
