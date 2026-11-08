package io.archsim.engine.simulator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.RandomStreams;
import io.archsim.engine.reporting.SimulatedRun;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.client.ClientConfig;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Workload;

/**
 * One prepared run, simulated: its timeline (the arrival period, then a drain), its links
 * and parts built, the workload's requests sent, and the simulation run in 200 chunks
 * until the end, or until nothing is in flight once the arrivals are over; its wall clock
 * checked every 4,096 events and after each chunk.
 */
final class RunSimulation {

	private static final int CHUNKS = 200;

	private static final int CHECK_EVERY = 4096;

	private static final double MS_PER_SECOND = 1000.0;

	private final ComponentRegistry registry;

	private final PreparedRun prepared;

	private final WallClock wallClock;

	RunSimulation(ComponentRegistry registry, PreparedRun prepared, WallClock wallClock) {
		this.registry = registry;
		this.prepared = prepared;
		this.wallClock = wallClock;
	}

	SimulatedRun simulate() {
		ArchitectureDocument resolved = this.prepared.resolved();
		Workload workload = resolved.workload();
		List<NodeSpec> sourceNodes = resolved.architecture()
			.nodes()
			.stream()
			.filter((node) -> this.registry.find(node.type()).orElseThrow().isTrafficSource())
			.toList();
		List<ClientConfig> sourceConfigs = sourceNodes.stream().map((node) -> (ClientConfig) config(node)).toList();
		double drainS = Drain.seconds(resolved, sourceConfigs, this.registry);
		double durationMs = workload.durationS() * MS_PER_SECOND;
		double endMs = durationMs + drainS * MS_PER_SECOND;
		SimContext context = new SimContext(new Simulation(), new RandomStreams(workload.seed()),
				new Timeline(endMs, workload.warmupS() * MS_PER_SECOND, durationMs), workload);
		ClientMetrics clients = new ClientMetrics(context.timeline());
		Assembly.build(context, resolved, this::config, clients);
		List<TrafficSourceRuntime> sources = sourceNodes.stream()
			.map((node) -> (TrafficSourceRuntime) context.component(node.id()))
			.toList();
		Arrivals arrivals = new Arrivals(context, workload, new ClientPicker(context, sources));
		context.simulation().start(arrivals::start);
		Chunks ran = runInChunks(context, durationMs, endMs);
		context.finish(ran.finalTime());
		return new SimulatedRun(this.prepared, context, clients, sources, ran.finalTime(), drainS, ran.events());
	}

	private Record config(NodeSpec node) {
		Map<String, Record> configs = this.prepared.validation().configs();
		return Objects.requireNonNull(configs.get(node.id()), node.id());
	}

	// runs to the end, or stops at a chunk's end after the arrivals once nothing is in
	// flight (only timers are left)
	private Chunks runInChunks(SimContext context, double durationMs, double endMs) {
		Simulation simulation = context.simulation();
		double step = endMs / CHUNKS;
		long events = 0;
		double time = 0;
		while (time < endMs) {
			time = Math.min(time + step, endMs);
			if (time > simulation.now()) {
				events += simulation.runUntil(time, CHECK_EVERY, () -> this.wallClock.check(simulation.now()));
			}
			this.wallClock.check(simulation.now());
			if (time >= durationMs && context.idle()) {
				break;
			}
		}
		return new Chunks(time, events);
	}

	/**
	 * Where a chunked run stopped.
	 *
	 * @param finalTime the simulated time it stopped at
	 * @param events how many events it processed
	 */
	private record Chunks(double finalTime, long events) {

	}

}
