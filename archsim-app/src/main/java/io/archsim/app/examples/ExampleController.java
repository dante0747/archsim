package io.archsim.app.examples;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * The example designs the template gallery offers.
 */
@RestController
class ExampleController {

	private final ExampleService examples;

	ExampleController(ExampleService examples) {
		this.examples = examples;
	}

	/**
	 * Every example: id, name, description, tags and scenario count.
	 * @return the summaries, in file-name order
	 */
	@GetMapping("/examples")
	List<ExampleSummary> list() {
		return this.examples.summaries();
	}

	/**
	 * One example's design, as its file has it.
	 * @param exampleId the example's id
	 * @return the design
	 */
	@GetMapping("/examples/{exampleId}")
	ObjectNode get(@PathVariable String exampleId) {
		return this.examples.design(exampleId);
	}

}
