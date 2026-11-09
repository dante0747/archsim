package io.archsim.engine.simulator;

import java.util.function.LongSupplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.RunReport;
import io.archsim.engine.reporting.ReportWriter;
import io.archsim.engine.reporting.SimulatedRun;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.run.RunPreparer;
import io.archsim.engine.simulation.Stationarity;
import io.archsim.engine.simulation.SteadyState;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * Simulates a design and writes its report, as the Python engine's {@code run_simulation}
 * did: the run prepared (resolved, sized, validated), simulated, and its report written;
 * or, for a quick check, answered from a steady sample when it can be.
 */
public final class Simulator {

	private final ComponentRegistry registry;

	private final RunPreparer preparer;

	private final ReportWriter writer;

	private final LongSupplier clock;

	/**
	 * A simulator.
	 * @param registry the component types
	 * @param prices the price snapshots a design's prices are pinned to
	 */
	public Simulator(ComponentRegistry registry, PriceSnapshots prices) {
		this(registry, prices, System::nanoTime);
	}

	/**
	 * A simulator that reads the wall clock from {@code clock}.
	 * @param registry the component types
	 * @param prices the price snapshots a design's prices are pinned to
	 * @param clock the wall clock, in nanoseconds, as {@link System#nanoTime()} reads it
	 */
	Simulator(ComponentRegistry registry, PriceSnapshots prices, LongSupplier clock) {
		this.registry = registry;
		this.preparer = new RunPreparer(registry, prices);
		this.writer = new ReportWriter(registry, prices);
		this.clock = clock;
	}

	/**
	 * Simulates a design as asked. A quick check of a steady load that runs much longer
	 * than it takes to settle simulates its warm-up and a steady sample, and answers from
	 * the sample when its halves agree; the report's {@code simulationMode} says so, or
	 * why the whole run was simulated instead.
	 * @param document the design
	 * @param request the scenario, seed, mode and limits
	 * @return the run's report ({@link ReportWriter})
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design is invalid,
	 * asks for an unknown scenario, or the seed is out of range
	 * @throws io.archsim.engine.run.SimulationLimitException when the run would generate
	 * more requests than its limit, or takes longer than its wall-clock limit
	 */
	public ObjectNode run(JsonNode document, RunRequest request) {
		return run(document, request, RunObserver.NONE);
	}

	/**
	 * Simulates a design as asked, telling {@code observer} how far it has got and
	 * stopping when it asks ({@link RunObserver}); else as
	 * {@link #run(JsonNode, RunRequest)}.
	 * @param document the design
	 * @param request the scenario, seed, mode and limits
	 * @param observer whoever waits for the run
	 * @return the run's report ({@link ReportWriter})
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design is invalid,
	 * asks for an unknown scenario, or the seed is out of range
	 * @throws io.archsim.engine.run.SimulationLimitException when the run would generate
	 * more requests than its limit, or takes longer than its wall-clock limit
	 * @throws SimulationCancelledException when {@code observer} asks it to stop
	 */
	public ObjectNode run(JsonNode document, RunRequest request, RunObserver observer) {
		if (request.mode() == SimulationMode.SAMPLED) {
			return runSampled(document, request, observer);
		}
		return runWhole(document, request, observer);
	}

	private ObjectNode runWhole(JsonNode document, RunRequest request, RunObserver observer) {
		WallClock wallClock = new WallClock(this.clock, request.limits().maxWallSeconds());
		PreparedRun prepared = this.preparer.prepare(document, request.scenarioId(), request.limits(), request.seed());
		SimulatedRun run = new RunSimulation(this.registry, prepared, wallClock, observer).simulate();
		ObjectNode report = this.writer.write(run, wallClock.elapsedS());
		observer.progress(1.0);
		return report;
	}

	// the warm-up and a steady sample simulated whole, answering for the run when the
	// sample is steady; else the run simulated whole, saying why
	private ObjectNode runSampled(JsonNode document, RunRequest request, RunObserver observer) {
		PreparedRun prepared = this.preparer.prepare(document, request.scenarioId(), request.limits(), request.seed());
		Double simulatedS = prepared.quickCheck().simulatedS();
		if (simulatedS == null) {
			return runWhole(document, request, observer);
		}
		ObjectNode sample = runWhole(Sampling.shortened(prepared.resolved(), simulatedS),
				RunRequest.of(null).withLimits(request.limits()), observer);
		SteadyState steady = Stationarity.judge(RunReport.read(sample));
		if (!steady.reached()) {
			ObjectNode full = runWhole(document, request, observer);
			full.set("simulationMode", Sampling.wholeInstead(prepared.resolved().workload(), simulatedS, steady));
			return full;
		}
		return Sampling.answer(sample, prepared, simulatedS, steady);
	}

}
