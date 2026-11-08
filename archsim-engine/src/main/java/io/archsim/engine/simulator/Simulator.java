package io.archsim.engine.simulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.reporting.ReportWriter;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.run.RunPreparer;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * Simulates a design and writes its report, as the Python engine's {@code run_simulation}
 * did: the run prepared (resolved, sized, validated), simulated, and its report written.
 */
public final class Simulator {

	private final ComponentRegistry registry;

	private final RunPreparer preparer;

	private final ReportWriter writer;

	/**
	 * A simulator.
	 * @param registry the component types
	 * @param prices the price snapshots a design's prices are pinned to
	 */
	public Simulator(ComponentRegistry registry, PriceSnapshots prices) {
		this.registry = registry;
		this.preparer = new RunPreparer(registry, prices);
		this.writer = new ReportWriter(registry);
	}

	/**
	 * Simulates a design as drawn, with its own seed, refused above the default limit of
	 * requests. The run has no wall-clock bound yet: the port adds it with the workers.
	 * @param document the design
	 * @return the run's report, as far as the port has come ({@link ReportWriter})
	 */
	public ObjectNode run(JsonNode document) {
		PreparedRun prepared = this.preparer.prepare(document, null, SimulationLimits.DEFAULT, null);
		return this.writer.write(new RunSimulation(this.registry, prepared).simulate());
	}

}
