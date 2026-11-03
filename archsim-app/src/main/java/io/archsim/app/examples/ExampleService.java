package io.archsim.app.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.web.ApiException;

/**
 * The example designs the template gallery offers.
 */
@Service
public class ExampleService {

	private final ExampleRepository examples;

	ExampleService(ExampleRepository examples) {
		this.examples = examples;
	}

	/**
	 * Every example, as the gallery lists it, in file-name order.
	 * @return the summaries
	 */
	List<ExampleSummary> summaries() {
		List<ExampleSummary> summaries = new ArrayList<>();
		for (Map.Entry<String, ObjectNode> example : this.examples.all().entrySet()) {
			summaries.add(summary(example.getKey(), example.getValue()));
		}
		return summaries;
	}

	/**
	 * One example's design, as its file has it.
	 * @param id the example's id
	 * @return the design, a copy the caller may change
	 * @throws ApiException {@code 404} when there is no such example
	 */
	public ObjectNode design(String id) {
		return this.examples.find(id)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown example '" + id + "'", List.of()));
	}

	private static ExampleSummary summary(String id, ObjectNode design) {
		JsonNode metadata = design.path("metadata");
		List<String> tags = metadata.path("tags").valueStream().map(JsonNode::asString).toList();
		JsonNode description = metadata.path("description");
		return new ExampleSummary(id, metadata.path("name").asString(id),
				description.isString() ? description.stringValue() : null, tags, design.path("scenarios").size());
	}

}
