package io.archsim.app.projects;

import java.util.Comparator;
import java.util.Optional;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.examples.ExampleService;

/**
 * Projects copied from the examples: the example to copy, and the copy someone already
 * made of it and never saved.
 */
@Component
class ExampleCopies {

	/** Compares numbers by value, as Python compares {@code 1} and {@code 1.0}. */
	private static final Comparator<JsonNode> NUMBERS_BY_VALUE = (one, other) -> {
		if (one.isNumber() && other.isNumber()) {
			return one.decimalValue().compareTo(other.decimalValue());
		}
		return one.equals(other) ? 0 : 1;
	};

	private final ExampleService examples;

	private final Designs designs;

	private final ProjectRepository projects;

	ExampleCopies(ExampleService examples, Designs designs, ProjectRepository projects) {
		this.examples = examples;
		this.designs = designs;
		this.projects = projects;
	}

	/**
	 * An example's design, to copy.
	 * @param exampleId the example
	 * @return a copy of its design
	 * @throws io.archsim.app.web.ApiException {@code 404} for an unknown example
	 */
	ObjectNode example(String exampleId) {
		return this.examples.design(exampleId);
	}

	/**
	 * The latest copy of an example someone made in a workspace and never saved, while it
	 * still equals the example: a copy of an older version of it is not handed back.
	 * @param workspaceId the workspace
	 * @param userId who made the copy
	 * @param exampleId the example
	 * @return the copy, if there is one
	 */
	Optional<Project> uneditedCopy(String workspaceId, String userId, String exampleId) {
		ObjectNode current = this.designs.normalize(example(exampleId), null);
		return this.projects.uneditedCopy(workspaceId, userId, exampleId)
			.filter((copy) -> copy.document().equals(NUMBERS_BY_VALUE, current));
	}

}
