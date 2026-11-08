package io.archsim.engine.runtime;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.model.document.FaultKind;
import io.archsim.model.document.NodeSpec;

/**
 * A part of the design during a run: how it handles the requests that reach it, what it
 * measures, and what it adds to the report. Handling is written as steps that continue
 * when the event they wait for has happened (see {@link io.archsim.engine.kernel}).
 */
public abstract class RuntimeComponent {

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
		return this.context.outEdges(id()).stream().filter((edge) -> edge.sourcePort().equals(port)).toList();
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
