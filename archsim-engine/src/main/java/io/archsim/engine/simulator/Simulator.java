package io.archsim.engine.simulator;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.reporting.ReportWriter;
import io.archsim.engine.reporting.SimulatedRun;
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

	private static final double NANOS_PER_SECOND = 1e9;

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
		this.writer = new ReportWriter(registry, prices);
	}

	/**
	 * Simulates a design, or one of its scenarios, with its own seed, refused above the
	 * default limit of requests. The run has no wall-clock bound yet: the port adds it
	 * with the workers.
	 * @param document the design
	 * @param scenarioId the scenario to simulate; {@code null} for the design as drawn
	 * @return the run's report ({@link ReportWriter})
	 * @throws UnsupportedOperationException for a part or a host machine the Java
	 * simulator does not simulate yet
	 */
	public ObjectNode run(JsonNode document, @Nullable String scenarioId) {
		long started = System.nanoTime();
		PreparedRun prepared = this.preparer.prepare(document, scenarioId, SimulationLimits.DEFAULT, null);
		SimulatedRun run = new RunSimulation(this.registry, prepared).simulate();
		return this.writer.write(run, (System.nanoTime() - started) / NANOS_PER_SECOND);
	}

}
