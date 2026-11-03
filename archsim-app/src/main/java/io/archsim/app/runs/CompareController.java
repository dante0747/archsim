package io.archsim.app.runs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Principal;
import io.archsim.app.web.QueryParameters;

/**
 * Comparing two finished runs.
 */
@RestController
class CompareController {

	private final Comparisons comparisons;

	CompareController(Comparisons comparisons) {
		this.comparisons = comparisons;
	}

	/**
	 * Compare two finished runs: KPI deltas, input differences and explanations.
	 * @param principal who is asking
	 * @param first the first run ({@code ?a=})
	 * @param second the second run ({@code ?b=})
	 * @return the comparison
	 */
	@GetMapping("/compare")
	JsonNode compare(Principal principal,
			@RequestParam(name = "a",
					required = false) @Parameter(schema = @Schema(type = "string")) @Nullable List<String> first,
			@RequestParam(name = "b",
					required = false) @Parameter(schema = @Schema(type = "string")) @Nullable List<String> second) {
		SequencedMap<String, @Nullable List<String>> query = new LinkedHashMap<>();
		query.put("a", first);
		query.put("b", second);
		List<String> ids = QueryParameters.required(query);
		return this.comparisons.compare(principal, ids.get(0), ids.get(1));
	}

}
