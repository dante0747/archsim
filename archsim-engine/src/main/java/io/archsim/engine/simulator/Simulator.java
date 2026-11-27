package io.archsim.engine.simulator;

import java.util.Set;
import java.util.function.LongSupplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.host.SiblingSpeeds;
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
 * or, for a quick check, answered from a steady sample when it can be. It keeps nothing
 * between runs, so one simulator is safe to share between threads, each simulating a run
 * of its own.
 */
public final class Simulator {

	private final ComponentRegistry registry;

	private final RunPreparer preparer;

	private final ReportWriter writer;

	private final LongSupplier clock;

	private final SiblingSpeeds siblingSpeeds;

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
		this.siblingSpeeds = new SiblingSpeeds(prices);
	}

	/**
	 * Simulates a design as asked. A quick check of a steady load that runs much longer
	 * than it takes to settle simulates its warm-up and a steady sample, and answers from
	 * the sample when its halves agree; the report's {@code simulationMode} says so, or
	 * why the whole run was simulated instead. Everything the request simulates, a quick
	 * check's sample and the whole run after it, shares its wall-clock limit, and the
	 * report's {@code provenance.wallSeconds} counts from the request's start.
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
		WallClock wallClock = new WallClock(this.clock, request.limits().maxWallSeconds());
		if (request.mode() == SimulationMode.SAMPLED) {
			return runSampled(document, request, observer, wallClock);
		}
		return runWhole(document, request, observer, wallClock);
	}

	private ObjectNode runWhole(JsonNode document, RunRequest request, RunObserver observer, WallClock wallClock) {
		PreparedRun prepared = this.preparer.prepare(document, request.scenarioId(), request.limits(), request.seed());
		SimulatedRun run = new RunSimulation(this.registry, prepared, wallClock, observer).simulate();
		ObjectNode report = this.writer.write(run, wallClock.elapsedS());
		observer.progress(1.0);
		return report;
	}

	// the warm-up and a steady sample simulated whole, answering for the run when the
	// sample is steady; else the run simulated whole, saying why
	private ObjectNode runSampled(JsonNode document, RunRequest request, RunObserver observer, WallClock wallClock) {
		PreparedRun prepared = this.preparer.prepare(document, request.scenarioId(), request.limits(), request.seed());
		Double simulatedS = prepared.quickCheck().simulatedS();
		if (simulatedS == null) {
			return runWhole(document, request, observer, wallClock);
		}
		// a sibling speed the run's resolution wrote is left for the sample's to write
		// again, so the sample's report says why its parts' vCPUs counted as they did
		Set<String> written = this.siblingSpeeds.writtenIn(prepared.document(), prepared.resolved());
		ObjectNode sample = runWhole(Sampling.shortened(prepared.resolved(), simulatedS, written),
				RunRequest.of(null).withLimits(request.limits()), observer, wallClock);
		SteadyState steady = Stationarity.judge(RunReport.read(sample));
		if (!steady.reached()) {
			ObjectNode full = runWhole(document, request, observer, wallClock);
			full.set("simulationMode", Sampling.wholeInstead(prepared.resolved().workload(), simulatedS, steady));
			return full;
		}
		return Sampling.answer(sample, prepared, simulatedS, steady);
	}

}
