package io.archsim.engine.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.report.NodeSummary;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.document.FaultKind;
import io.archsim.model.document.NodeSpec;

/**
 * A part of the design during a run: how it handles the requests that reach it, what it
 * measures, and what it adds to the report. Handling is written as steps that continue
 * when the event they wait for has happened (see {@link io.archsim.engine.kernel}).
 */
public abstract class RuntimeComponent {

	private static final double MS_PER_SECOND = 1000.0;

	private final SimContext context;

	private final NodeSpec node;

	private final NodeMetrics metrics;

	/**
	 * A part at the start of a run.
	 * @param context the run
	 * @param node the part
	 * @param slots how many slots it has; empty when it has none
	 */
	protected RuntimeComponent(SimContext context, NodeSpec node, OptionalInt slots) {
		this.context = context;
		this.node = node;
		this.metrics = new NodeMetrics(context.timeline(), slots);
	}

	/**
	 * Handles a request that reached the part.
	 * @param request the request
	 * @param done what to do with how it ended
	 */
	public abstract void handle(Request request, Consumer<Outcome> done);

	public final String id() {
		return this.node.id();
	}

	public final NodeSpec node() {
		return this.node;
	}

	public final NodeMetrics metrics() {
		return this.metrics;
	}

	/**
	 * The part's links leaving a port.
	 * @param port such as {@code out}
	 * @return the links, in the design's order
	 */
	public final List<EdgeRuntime> outEdges(String port) {
		return this.context.outEdges(id(), port);
	}

	/**
	 * Takes a request on one of the part's instances, as a load balancer sends it there.
	 * @param request the request
	 * @param index the instance, from 0
	 * @param done what to do with the outcome
	 */
	public void handleOn(Request request, int index, Consumer<Outcome> done) {
		handle(request, done);
	}

	/**
	 * How many instances the part has, for a load balancer to choose from.
	 * @return one, unless the part has replicas
	 */
	public int instanceCount() {
		return 1;
	}

	/**
	 * The requests one instance holds, for a load balancer's least-connections policy.
	 * @param index the instance, from 0
	 * @return the requests in flight at the part, unless it counts them per instance
	 */
	public long instanceLoad(int index) {
		return this.metrics.inflight().value();
	}

	/**
	 * The gauge whose level over the part's slots is its utilization.
	 * @return its busy slots, unless it says otherwise
	 */
	public Gauge occupancy() {
		return this.metrics.busy();
	}

	/**
	 * Whether the part's behaviour depends on the requests' keys, so the run draws a key
	 * for each request from the workload's keyspace.
	 * @return {@code false} unless the part says so
	 */
	public boolean usesKeys() {
		return false;
	}

	/**
	 * What a cache-aside caller sends the part after it read the data a lookup here
	 * missed: the value written back.
	 * @return the write-back's handler; none unless the part takes one
	 */
	public Optional<Handler> writeBack() {
		return Optional.empty();
	}

	/**
	 * Whether work not tied to a call in flight remains, such as queued messages.
	 * @return {@code false} unless the part has such work
	 */
	public boolean backgroundBusy() {
		return false;
	}

	/**
	 * Integrates what the part measured up to the end of the run.
	 * @param end the run's end
	 */
	public void finish(double end) {
		this.metrics.finish(end);
	}

	/**
	 * Whether the part counts its CPU work apart from its busy slots, so the report gives
	 * its CPU utilization beside its utilization.
	 * @return {@code false} unless the part says so
	 */
	public boolean splitsCpu() {
		return false;
	}

	/**
	 * The throughput ceilings the part's resource specs set, such as its CPU's or its
	 * memory's, in the order the part names them.
	 * @param holdMs the mean time a request held a slot in the window, if any held one
	 * @param serviceMs the mean service time in the window, if any request was served
	 * @return the ceilings in requests per second, by what sets them; none by default
	 */
	public SequencedMap<String, Double> resourceCapacity(OptionalDouble holdMs, OptionalDouble serviceMs) {
		return new LinkedHashMap<>();
	}

	/**
	 * The throughput at which the part's slots fill when each request holds one as long
	 * as the window's did on average.
	 * @param slots its slots
	 * @param holdMs the mean time a request held one
	 * @param serviceMs the mean time of its own work, part of the holding time, if any
	 * @return requests per second: the slots times a second over the holding time, unless
	 * the part's time per request changes with its load
	 */
	public double slotCapacity(double slots, double holdMs, OptionalDouble serviceMs) {
		return slots * MS_PER_SECOND / holdMs;
	}

	/**
	 * The part's own figures for its summary, after the ones every part has, as the
	 * report writes them: a count as a {@code Long} or an {@code Integer}, any other
	 * figure as a {@code Double}, already rounded as the part rounds it.
	 * @return the figures by name, in order, {@code null} for one the part has not got;
	 * none by default
	 */
	public SequencedMap<String, @Nullable Number> extraSummary() {
		return new LinkedHashMap<>();
	}

	/**
	 * The part's own time series, one value per bucket of the whole timeline, which the
	 * report rounds and cuts where the run stopped.
	 * @return the series by name, in order; none by default
	 */
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		return new LinkedHashMap<>();
	}

	/**
	 * A series of a part's figures, as {@link #extraTimeseries} gives one.
	 * @param values one figure per bucket of the whole timeline
	 * @return the figures, in order
	 */
	public static List<@Nullable Double> series(double[] values) {
		List<@Nullable Double> series = new ArrayList<>(values.length);
		for (double value : values) {
			series.add(value);
		}
		return series;
	}

	/**
	 * The calculations behind the part's own figures, after the queueing formulas of
	 * every part that serves requests in slots.
	 * @param summary the part's summary in the report
	 * @return the formulas; none by default
	 */
	public List<Formula> explain(NodeSummary summary) {
		return List.of();
	}

	/**
	 * The behaviours the part's technology brings that are in effect.
	 * @return the traits; none by default
	 */
	public List<TechnologyTrait> behaviour() {
		return List.of();
	}

	/**
	 * A service or processing time drawn from a distribution, stretched by a slowdown
	 * planned for the part now.
	 * @param sampler the distribution's sampler
	 * @param random the stream to draw from
	 * @return milliseconds
	 */
	protected final double sampleTime(Sampler sampler, PythonRandom random) {
		double value = sampler.sample(random);
		return this.context.fault(FaultKind.SLOWDOWN, id()).map((slowdown) -> value * slowdown.factor()).orElse(value);
	}

	protected final SimContext context() {
		return this.context;
	}

	protected final Simulation simulation() {
		return this.context.simulation();
	}

	protected final double now() {
		return this.context.simulation().now();
	}

}
