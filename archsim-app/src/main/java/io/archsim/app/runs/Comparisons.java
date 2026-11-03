package io.archsim.app.runs;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;

/**
 * Compares two finished runs: KPI deltas, input differences and explanations
 * ({@code compare_reports}).
 */
@Service
class Comparisons {

	private final Runs runs;

	private final EngineService engine;

	Comparisons(Runs runs, EngineService engine) {
		this.runs = runs;
		this.engine = engine;
	}

	/**
	 * Compares two runs.
	 * @param principal who asks
	 * @param first the first run's id
	 * @param second the second run's id
	 * @return the comparison, with the runs' ids under {@code runs}
	 * @throws ApiException {@code 409} unless both runs succeeded
	 */
	JsonNode compare(Principal principal, String first, String second) {
		StoredRun firstRun = this.runs.require(principal, first, WorkspaceRole.VIEWER);
		StoredRun secondRun = this.runs.require(principal, second, WorkspaceRole.VIEWER);
		JsonNode firstReport = report(firstRun);
		JsonNode secondReport = report(secondRun);
		ObjectNode comparison = this.engine.compare(firstReport, secondReport, firstRun.view().name(),
				secondRun.view().name());
		comparison.putObject("runs").put("a", firstRun.id()).put("b", secondRun.id());
		return comparison;
	}

	private JsonNode report(StoredRun run) {
		RunView view = run.view();
		return this.runs.reportDocument(run)
			.filter((report) -> view.succeeded())
			.orElseThrow(
					() -> ApiException.conflict("Run " + view.shortId() + " is " + view.status() + "; cannot compare"));
	}

}
