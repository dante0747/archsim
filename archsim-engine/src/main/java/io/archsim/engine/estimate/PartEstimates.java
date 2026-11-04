package io.archsim.engine.estimate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.queueing.Moments;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * Each part's estimate, made the parts it calls first, and how long a request spends in
 * each part with its own calls, which the parts that call it add to their own time. Made
 * whole on creation, and read-only after.
 */
final class PartEstimates {

	private final EstimateGraph graph;

	private final LoadEstimate loads;

	private final Map<String, Double> speeds;

	private final Set<String> onPath;

	private final Map<String, Moments> links = new HashMap<>();

	/**
	 * A request's time in each part, its own calls included; an op missing is unknown.
	 */
	private final Map<String, Map<Op, Moments>> residence = new HashMap<>();

	/**
	 * Each part's wait and own time per request, in the order the parts were estimated.
	 */
	private final Map<String, Map<Op, Moments>> ownTimes = new LinkedHashMap<>();

	/** Each part's estimate, in the order the parts were estimated. */
	private final Map<String, PartEstimate> estimates = new LinkedHashMap<>();

	private PartEstimates(EstimateGraph graph, LoadEstimate loads, Map<String, Double> speeds, Set<String> onPath) {
		this.graph = graph;
		this.loads = loads;
		this.speeds = speeds;
		this.onPath = onPath;
		for (EdgeSpec edge : graph.architecture().edges()) {
			this.links.put(edge.id(), Moments.of(edge.config().latencyMs()).times(2));
		}
	}

	/**
	 * Estimates every part of a design, the parts it calls first.
	 * @param graph the design
	 * @param loads the parts' and links' loads at the peak
	 * @param speeds the speed of each part whose CPU threads are slowed, by its id
	 * @param onPath the parts on a client's request path
	 * @return the estimates
	 */
	static PartEstimates of(EstimateGraph graph, LoadEstimate loads, Map<String, Double> speeds, Set<String> onPath) {
		PartEstimates estimates = new PartEstimates(graph, loads, speeds, onPath);
		List<String> order = graph.callOrder();
		for (int i = order.size() - 1; i >= 0; i--) {
			estimates.estimate(order.get(i));
		}
		return estimates;
	}

	/**
	 * The parts' estimates.
	 * @return each part's estimate by its id, in the order they were made
	 */
	Map<String, PartEstimate> estimates() {
		return Collections.unmodifiableMap(this.estimates);
	}

	/**
	 * The parts' estimates in the design's order, a part repeated for each part with its
	 * id, each with its time per client request when the latency is estimated.
	 * @param perRequestMs each part's wait and own time per client request, by its id
	 * @return the estimates
	 */
	List<PartEstimate> inDesignOrder(Map<String, @Nullable Double> perRequestMs) {
		List<PartEstimate> ordered = new ArrayList<>();
		for (NodeSpec node : this.graph.architecture().nodes()) {
			PartEstimate estimate = this.estimates.get(node.id());
			if (estimate != null) {
				ordered.add(withPerRequestMs(estimate, perRequestMs));
			}
		}
		return List.copyOf(ordered);
	}

	/**
	 * Each part's wait and own time per request.
	 * @return the times by part id, in the order the parts were estimated
	 */
	Map<String, Map<Op, Moments>> ownTimes() {
		return Collections.unmodifiableMap(this.ownTimes);
	}

	/**
	 * A link's network time, both ways.
	 * @param id the link's id
	 * @return its moments
	 */
	Moments link(String id) {
		return Objects.requireNonNull(this.links.get(id), id);
	}

	/**
	 * A part's estimate with its time per client request, when it has one.
	 * @param estimate the part's estimate
	 * @param perRequestMs each part's time per client request, by its id
	 * @return the estimate, with the time when the part has one
	 */
	private static PartEstimate withPerRequestMs(PartEstimate estimate, Map<String, @Nullable Double> perRequestMs) {
		if (!perRequestMs.containsKey(estimate.id())) {
			return estimate;
		}
		Double ms = perRequestMs.get(estimate.id());
		return switch (estimate) {
			case EstimatedPart estimated -> estimated.withPerRequestMs(ms);
			case UnestimatedPart unestimated -> unestimated.withPerRequestMs(ms);
		};
	}

	/**
	 * The time of the calls a part makes for one request of a kind, in order, each
	 * weighted by how often it is made.
	 * @param id the part's id
	 * @param op reads or writes
	 * @param here the part's load
	 * @return the calls' moments; {@code null} when a call goes to a part with no
	 * estimate
	 */
	@Nullable Moments calls(String id, Op op, NodeLoad here) {
		double rate = op.rate(here);
		Moments total = Moments.ZERO;
		if (rate <= 0) {
			return total;
		}
		for (EdgeSpec edge : this.graph.calls(id)) {
			NodeLoad sent = this.loads.edges().get(edge.id());
			if (sent == null) {
				return null;
			}
			double often = op.rate(sent) / rate;
			if (often > 0) {
				Moments target = this.residence.getOrDefault(edge.target(), Map.of()).get(op);
				if (target == null) {
					return null;
				}
				total = total.plus(link(edge.id()).plus(target).times(often));
			}
		}
		return total;
	}

	/**
	 * Estimates one part, the parts it calls already estimated.
	 * @param id the part's id
	 */
	private void estimate(String id) {
		if (this.graph.isClient(id)) {
			return;
		}
		NodeSpec node = this.graph.node(id);
		this.residence.put(id, new EnumMap<>(Op.class));
		Optional<NodeLoad> here = this.loads.node(id);
		if (here.isEmpty()) {
			note(node, null, "Its load depends on the run (a cache's hit ratio upstream).");
			return;
		}
		Optional<PartShape> shape = this.graph.config(id)
			.flatMap((config) -> PartShapes.of(config, here.get()))
			.map((found) -> slowed(id, found));
		if (shape.isEmpty()) {
			note(node, here.get(), "No capacity model for this kind of part.");
			return;
		}
		new Part(node, here.get(), shape.get()).estimate();
	}

	/**
	 * A part's shape at its threads' speed.
	 * @param id the part's id
	 * @param shape its shape at full speed
	 * @return the shape slowed to its speed, or as it is at full speed
	 */
	private PartShape slowed(String id, PartShape shape) {
		Double speed = this.speeds.get(id);
		if (speed == null) {
			return shape;
		}
		double cpuShare = switch (this.graph.config(id).orElseThrow()) {
			case ServiceConfig service -> service.cpuShare();
			case DatabaseConfig database -> database.cpuShare();
			default -> 1.0;
		};
		return shape.slowedTo(speed, cpuShare);
	}

	private void note(NodeSpec node, @Nullable NodeLoad here, String note) {
		Double arrival = (here != null) ? Rounded.of(here.total(), Rounded.RATE) : null;
		this.estimates.put(node.id(), new UnestimatedPart(node.id(), node.name(), node.type(),
				this.onPath.contains(node.id()), arrival, PartStatus.UNKNOWN, note, null));
	}

	/** One part being estimated: its load and shape, and what it holds slots for. */
	private final class Part {

		private final NodeSpec node;

		private final NodeLoad here;

		private final PartShape shape;

		private final boolean queue;

		private final Map<Op, @Nullable Moments> down = new EnumMap<>(Op.class);

		/** A request's time in the part with its calls, for each kind known. */
		private final Map<Op, Moments> stays;

		Part(NodeSpec node, NodeLoad here, PartShape shape) {
			this.node = node;
			this.here = here;
			this.shape = shape;
			this.queue = MessageQueueSpec.TYPE.equals(node.type());
			this.stays = Objects.requireNonNull(PartEstimates.this.residence.get(node.id()), node.id());
		}

		void estimate() {
			String id = this.node.id();
			Moments publish = this.shape.publish();
			if (this.queue && publish != null) {
				// the producer waits for the publish only; consumers work in the
				// background
				Map<Op, Moments> waits = Map.of(Op.READ, publish, Op.WRITE, publish);
				this.stays.putAll(waits);
				PartEstimates.this.ownTimes.put(id, new EnumMap<>(waits));
			}
			Map<Op, @Nullable Moments> hold = new EnumMap<>(Op.class);
			for (Op op : Op.values()) {
				Moments calls = calls(id, op, this.here);
				this.down.put(op, calls);
				hold.put(op, holdTime(op, calls));
			}
			if (waitsOnUnknown(hold)) {
				note(this.node, this.here, "It waits on a part that is overloaded or has no estimate.");
				return;
			}
			Capacity capacity = Capacity.of(this.shape, this.here, hold);
			EstimatedPart estimate = capacity.estimate(this.node, PartEstimates.this.onPath.contains(id), this.here,
					this.shape);
			if (capacity.stable() && !this.queue) {
				recordTimes(capacity.queueWait());
				estimate = estimate.withMeanResidenceMs(meanResidenceMs());
			}
			PartEstimates.this.estimates.put(id, estimate);
		}

		/**
		 * How long a request of a kind holds a slot: the part's own time, and its calls'
		 * when it holds the slot through them.
		 * @param op reads or writes
		 * @param calls the time of the part's calls; {@code null} when unknown
		 * @return the time; {@code null} when it waits on calls of unknown time
		 */
		private @Nullable Moments holdTime(Op op, @Nullable Moments calls) {
			if (!this.shape.blocks()) {
				return this.shape.own(op);
			}
			if (calls == null) {
				return null;
			}
			return this.shape.own(op).plus(calls);
		}

		private boolean waitsOnUnknown(Map<Op, @Nullable Moments> hold) {
			boolean readsUnknown = this.here.read() > 0 && hold.get(Op.READ) == null;
			boolean writesUnknown = this.here.write() > 0 && hold.get(Op.WRITE) == null;
			return readsUnknown || writesUnknown;
		}

		/**
		 * A request's wait and own time in the part, and with its calls, for each kind.
		 * @param wait the wait for a slot
		 */
		private void recordTimes(Moments wait) {
			String id = this.node.id();
			Map<Op, Moments> own = new EnumMap<>(Op.class);
			PartEstimates.this.ownTimes.put(id, own);
			for (Op op : Op.values()) {
				Moments calls = this.down.get(op);
				if (calls != null) {
					Moments mine = wait.plus(this.shape.own(op));
					Moments extra = writeBack(op, wait);
					own.put(op, mine.plus(extra));
					this.stays.put(op, mine.plus(calls).plus(extra));
				}
			}
		}

		/**
		 * A read-through cache's extra command after a miss: it writes the value back.
		 * @param op reads or writes
		 * @param wait the wait for a slot
		 * @return the write-back's share of a read's time; zero otherwise
		 */
		private Moments writeBack(Op op, Moments wait) {
			if (!this.shape.populate() || op != Op.READ) {
				return Moments.ZERO;
			}
			double missed = FloatSum.of(PartEstimates.this.graph.calls(this.node.id())
				.stream()
				.filter((edge) -> "miss".equals(edge.sourcePort()))
				.map((edge) -> PartEstimates.this.loads.edges().get(edge.id()))
				.filter((sent) -> sent != null)
				.mapToDouble(NodeLoad::read));
			if (this.here.read() > 0 && missed > 0) {
				return wait.plus(this.shape.own(Op.READ)).times(missed / this.here.read());
			}
			return Moments.ZERO;
		}

		/**
		 * How long a request stays in the part, waiting, working and in its own calls.
		 * @return the mean, rounded; {@code null} when unknown
		 */
		private @Nullable Double meanResidenceMs() {
			double total = this.here.total();
			List<Double> terms = new ArrayList<>();
			for (Op op : Op.values()) {
				Moments stay = this.stays.get(op);
				double rate = op.rate(this.here);
				if (rate > 0) {
					if (stay == null) {
						return null;
					}
					terms.add(rate * stay.mean());
				}
			}
			if (total <= 0) {
				return null;
			}
			return Rounded.of(FloatSum.of(terms.stream().mapToDouble(Double::doubleValue)) / total, Rounded.TIME);
		}

	}

}
