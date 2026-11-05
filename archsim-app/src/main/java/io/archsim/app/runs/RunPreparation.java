package io.archsim.app.runs;

import org.springframework.stereotype.Component;

import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.run.RunPreparer;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.DesignDocuments;
import io.archsim.model.issue.DocumentInvalidException;

/**
 * Prepares a run to be queued, in Java (archsim-engine's {@code RunPreparer}): the design
 * to store with it, its scenario and seed, and whether a quick check can answer it.
 */
@Component
class RunPreparation {

	private final RunPreparer preparer;

	RunPreparation(RunPreparer preparer) {
		this.preparer = preparer;
	}

	/**
	 * Prepares a run.
	 * @param request the design, scenario, limits and seed
	 * @return the run as planned
	 * @throws DocumentInvalidException when the design cannot be simulated
	 * @throws SimulationLimitException when the run is over its limits
	 */
	PlannedRun prepare(PrepareRequest request) {
		PreparedRun run = this.preparer.prepare(request.document(), request.scenarioId(),
				new SimulationLimits(request.maxRequests(), request.maxWallSeconds()), request.seed());
		return new PlannedRun(run.scenarioId(), run.scenarioName(), run.seed(), DesignDocuments.toJson(run.document()),
				run.quickCheck());
	}

}
