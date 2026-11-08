package io.archsim.engine.check;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.report.RunReport;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.DesignDocuments;
import io.archsim.model.issue.DocumentInvalidException;

/**
 * A design checked: simulated once and judged by its objectives, against a baseline when
 * there is one (a baseline that cannot be simulated is noted, and nothing compared with
 * it), with both breaking points on one load grid when asked.
 */
public final class CheckRunner {

	private final Simulator simulator;

	private final KneeSweeps kneeSweeps;

	/**
	 * A check runner.
	 * @param simulator the simulator each run runs on
	 * @param kneeSweeps what sweeps the breaking points
	 */
	public CheckRunner(Simulator simulator, KneeSweeps kneeSweeps) {
		this.simulator = simulator;
		this.kneeSweeps = kneeSweeps;
	}

	/**
	 * What a check measures of a design: one run's figures, judged by its objectives.
	 * @param document the design
	 * @param scenarioId the scenario to simulate; {@code null} for the design as drawn
	 * @param limits the run's limits
	 * @return the measurement, without a breaking point
	 * @throws DocumentInvalidException when the design is invalid or has no such scenario
	 * @throws SimulationLimitException when the run is over a limit
	 */
	public Measurement measure(JsonNode document, @Nullable String scenarioId, SimulationLimits limits) {
		JsonNode report = this.simulator.run(document, RunRequest.of(scenarioId).withLimits(limits));
		return Measurement.of(DesignDocuments.load(document), scenarioId, RunReport.read(report));
	}

	/**
	 * What a check measures of a design, with its breaking point.
	 * @param document the design
	 * @param scenarioId the scenario to simulate; {@code null} for the design as drawn
	 * @param limits each run's limits
	 * @return the measurement, with the breaking point on the design's own grid
	 * @throws DocumentInvalidException when the design is invalid or has no such scenario
	 * @throws SimulationLimitException when a run is over a limit
	 */
	public Measurement measureWithKnee(JsonNode document, @Nullable String scenarioId, SimulationLimits limits) {
		return measure(document, scenarioId, limits)
			.withKnee(this.kneeSweeps.of(List.of(document), scenarioId, limits).getFirst());
	}

	/**
	 * Checks a design against its objectives and, when there is one, its baseline.
	 * @param document the design
	 * @param baseline the design it is compared with; {@code null} for none
	 * @param request the scenario, settings, sweep and limits
	 * @return the check
	 * @throws DocumentInvalidException when the design is invalid or has no such scenario
	 * @throws SimulationLimitException when one of the design's runs, or of the steps
	 * that sweep its or the baseline's breaking point, is over a limit
	 */
	public CheckResult check(JsonNode document, @Nullable JsonNode baseline, CheckRequest request) {
		Measurement current = measure(document, request.scenarioId(), request.limits());
		List<String> notes = new ArrayList<>();
		Measurement compared = null;
		if (baseline != null) {
			try {
				compared = measure(baseline, request.scenarioId(), request.limits());
			}
			catch (DocumentInvalidException | SimulationLimitException ex) {
				// the baseline may be an older design this engine cannot read or run
				notes.add("The baseline could not be simulated (" + ex.getMessage() + "); no regression check.");
			}
		}
		if (request.sweepsBreakingPoints()) {
			List<JsonNode> documents = (compared != null && baseline != null) ? List.of(document, baseline)
					: List.of(document);
			List<KneeFinding> knees = this.kneeSweeps.of(documents, request.scenarioId(), request.limits());
			current = current.withKnee(knees.getFirst());
			compared = (compared != null) ? compared.withKnee(knees.get(1)) : null;
		}
		return CheckResult.of(current, compared, request.settings(), notes);
	}

}
