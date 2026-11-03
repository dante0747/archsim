package io.archsim.app.runs;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.engine.PrepareRequest;
import io.archsim.app.engine.PreparedRun;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceInfo;
import io.archsim.app.workspaces.Workspaces;

/**
 * Plans a run before it is queued: the limits of its workspace, and what the engine
 * prepares to simulate. A design the engine cannot simulate, or a run over its limits, is
 * refused here, before anything is counted or queued.
 */
@Component
class RunPlanner {

	private final EngineService engine;

	private final Workspaces workspaces;

	private final WorkspaceLimits limits;

	RunPlanner(EngineService engine, Workspaces workspaces, WorkspaceLimits limits) {
		this.engine = engine;
		this.workspaces = workspaces;
		this.limits = limits;
	}

	/**
	 * What a workspace's runs may do.
	 * @param workspaceId the workspace
	 * @return the workspace, the largest run it may start and the most seeds in a group
	 */
	Plan plan(String workspaceId) {
		WorkspaceInfo workspace = this.workspaces.get(workspaceId);
		return new Plan(workspace, this.limits.runLimits(workspace.kind()), this.limits.maxSeeds(workspace.kind()));
	}

	/**
	 * Prepares a run.
	 * @param request what to prepare
	 * @return what the engine prepared
	 * @throws ApiException {@code 422} when the engine cannot simulate it, with its
	 * issues, or the run is over its limits
	 */
	PreparedRun prepare(PrepareRequest request) {
		try {
			return this.engine.prepare(request);
		}
		catch (EngineRefusalException ex) {
			throw refusal(ex, "Cannot simulate: ");
		}
	}

	/**
	 * Prepares one scenario of a batch, naming it if it cannot be simulated.
	 * @param request what to prepare
	 * @return what the engine prepared
	 * @throws ApiException {@code 422} naming the scenario ({@code base} for the design
	 * itself)
	 */
	PreparedRun prepareScenario(PrepareRequest request) {
		try {
			return this.engine.prepare(request);
		}
		catch (EngineRefusalException ex) {
			String scenario = request.scenarioId();
			String named = (scenario != null && !scenario.isEmpty()) ? scenario : "base";
			throw refusal(ex, "Cannot simulate scenario '" + named + "': ");
		}
	}

	/**
	 * Refuses a quick check of a run that sampled mode cannot answer.
	 * @param prepared the run
	 * @throws ApiException {@code 422} saying why
	 */
	static void requireQuickCheck(PreparedRun prepared) {
		if (!prepared.quickCheck().eligible()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"Cannot run a quick check: " + prepared.quickCheck().reason(), List.of());
		}
	}

	private static ApiException refusal(EngineRefusalException ex, String prefix) {
		String message = ex.overLimits() ? ex.engineMessage() : prefix + ex.engineMessage();
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message, ex.issues());
	}

	/**
	 * What a workspace's runs may do.
	 *
	 * @param workspace the workspace
	 * @param limits the largest run it may start
	 * @param maxSeeds the most runs in one seed group
	 */
	record Plan(WorkspaceInfo workspace, WorkspaceLimits.RunLimits limits, int maxSeeds) {

		/**
		 * A request to prepare a run within these limits.
		 * @param document the design
		 * @param scenarioId the scenario, if any
		 * @param seed the seed that replaces the design's, if any
		 * @return the request
		 */
		PrepareRequest request(JsonNode document, @Nullable String scenarioId, @Nullable Long seed) {
			return new PrepareRequest(document, scenarioId, this.limits.maxRequests(), this.limits.maxWallSeconds(),
					seed);
		}

	}

}
