package io.archsim.engine.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.RandomStreams;
import io.archsim.model.document.Fault;
import io.archsim.model.document.FaultKind;

/**
 * Everything a part needs during a run: the clock, the random streams, the other parts
 * and the links between them, the planned faults, the calls on the wire and the sampled
 * requests' traces.
 */
public final class SimContext {

	private final Simulation simulation;

	private final RandomStreams streams;

	private final Timeline timeline;

	private final Faults faults;

	private final Map<String, RuntimeComponent> components = new LinkedHashMap<>();

	private final Map<String, EdgeRuntime> edges = new LinkedHashMap<>();

	private final Map<String, List<EdgeRuntime>> outEdges = new HashMap<>();

	private final List<TracedRequest> traces = new ArrayList<>();

	private final Calls calls = new Calls(this);

	private int inTransit;

	/**
	 * A run's context, with no part or link yet.
	 * @param simulation the run's clock
	 * @param streams the run's random streams
	 * @param timeline the run's timeline
	 * @param faults the faults planned for the run
	 */
	public SimContext(Simulation simulation, RandomStreams streams, Timeline timeline, List<Fault> faults) {
		this.simulation = simulation;
		this.streams = streams;
		this.timeline = timeline;
		this.faults = new Faults(faults);
	}

	/**
	 * Adds a link, after the links added before it.
	 * @param edge the link
	 */
	public void addEdge(EdgeRuntime edge) {
		this.edges.put(edge.id(), edge);
		this.outEdges.computeIfAbsent(edge.sourceId(), (ignored) -> new ArrayList<>()).add(edge);
	}

	/**
	 * Adds a part, after the parts added before it.
	 * @param component the part
	 */
	public void addComponent(RuntimeComponent component) {
		this.components.put(component.id(), component);
	}

	/**
	 * A named random stream of the run.
	 * @param name such as {@code node:db:service}
	 * @return a new generator at the start of the stream
	 */
	public PythonRandom stream(String name) {
		return this.streams.stream(name);
	}

	/**
	 * The fault of a kind planned on a target now.
	 * @param kind the kind
	 * @param target the part's or link's id
	 * @return the fault, empty when none is active
	 */
	public Optional<Fault> fault(FaultKind kind, String target) {
		return this.faults.active(kind, target, this.simulation.now());
	}

	/**
	 * Whether nothing is in flight anywhere: no call on the wire, no request at a part
	 * and no background work (timers do not count).
	 * @return {@code true} when idle
	 */
	public boolean idle() {
		if (this.inTransit != 0) {
			return false;
		}
		return this.components.values()
			.stream()
			.allMatch((component) -> component.metrics().inflight().value() == 0 && !component.backgroundBusy());
	}

	/**
	 * Keeps a sampled request's trace for the report.
	 * @param trace the trace
	 */
	public void trace(TracedRequest trace) {
		this.traces.add(trace);
	}

	public Simulation simulation() {
		return this.simulation;
	}

	public Timeline timeline() {
		return this.timeline;
	}

	public Calls calls() {
		return this.calls;
	}

	/**
	 * A part.
	 * @param id its id
	 * @return the part
	 * @throws NullPointerException for a part the run does not have
	 */
	public RuntimeComponent component(String id) {
		return Objects.requireNonNull(this.components.get(id), id);
	}

	/**
	 * The parts, in the design's order.
	 * @return the parts
	 */
	public Collection<RuntimeComponent> components() {
		return Collections.unmodifiableCollection(this.components.values());
	}

	/**
	 * A link.
	 * @param id its id
	 * @return the link
	 * @throws NullPointerException for a link the run does not have
	 */
	public EdgeRuntime edge(String id) {
		return Objects.requireNonNull(this.edges.get(id), id);
	}

	/**
	 * The sampled requests' traces, in the order their clients finished with them.
	 * @return the traces
	 */
	public List<TracedRequest> traces() {
		return Collections.unmodifiableList(this.traces);
	}

	List<EdgeRuntime> outEdges(String nodeId) {
		return this.outEdges.getOrDefault(nodeId, List.of());
	}

	void enterTransit() {
		this.inTransit++;
	}

	void leaveTransit() {
		this.inTransit--;
	}

}
