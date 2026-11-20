package io.archsim.engine.estimate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.queueing.Moments;
import io.archsim.engine.queueing.QueueWait;
import io.archsim.model.document.NodeSpec;

/**
 * How much of a part's capacity the peak takes. Busy is the share of its slots or CPU in
 * use, what a run's utilization measures; a rate or throughput limit is not work: its
 * share says how close the part is to turning requests away (its load), which can bind
 * first without the part being busy at all. On its own hyperthread pairs a part's CPU
 * fills where every vCPU is busy at the sibling speed, so the CPU's share of its load is
 * at least the share it would take then (ADR-0091). Each request takes a slot, and so
 * does a read-through cache's fill of a key it missed.
 *
 * @param meanHoldMs how long a request or a fill holds a slot
 * @param slotUtilization the share of its slots in use; {@code null} without slots
 * @param utilization the larger of the slot and CPU shares; {@code null} for neither
 * @param load the largest of the slot, CPU and rate-limit shares, the CPU's on a part's
 * own pairs at least its share at the sibling speed; {@code null} for none
 * @param limitedBy which share is the largest
 * @param stable whether the part keeps up: its load under 1 and its queue stable
 * @param waitProbability the chance a request or a fill waits for a slot
 * @param queueWait the wait for a slot
 */
record Capacity(double meanHoldMs, @Nullable Double slotUtilization, @Nullable Double utilization,
		@Nullable Double load, @Nullable Limit limitedBy, boolean stable, double waitProbability, Moments queueWait) {

	private static final double MILLIS_PER_SECOND = 1000.0;

	/**
	 * From this share of a limit a part counts as busy, the design review's CPU
	 * threshold.
	 */
	private static final double BUSY = 0.8;

	/** No wait at all. */
	private static final QueueWait NO_WAIT = new QueueWait(0.0, Moments.ZERO);

	/**
	 * The capacity a part's load takes.
	 * @param shape how the part serves requests
	 * @param here its load
	 * @param hold how long a request of each kind holds a slot; every kind it gets is
	 * known
	 * @return the capacity taken
	 */
	static Capacity of(PartShape shape, NodeLoad here, Map<Op, @Nullable Moments> hold) {
		double arrivals = here.total() + shape.fills();
		double meanHold = (arrivals > 0) ? busy(shape, here, hold) / arrivals : 0.0;
		int slots = slots(shape);
		Double slotShare = (slots != 0) ? arrivals * meanHold / (MILLIS_PER_SECOND * slots * shape.pools()) : null;
		Double utilization = larger(slotShare, shape.cpu());
		Map<Limit, Double> shares = new LinkedHashMap<>();
		putIfKnown(shares, Limit.SLOTS, slotShare);
		putIfKnown(shares, Limit.CPU, shape.cpuLoad());
		putIfKnown(shares, Limit.RATE_LIMIT, shape.limit());
		Map.Entry<Limit, Double> largest = largest(shares);
		Double load = (largest != null) ? largest.getValue() : null;
		Optional<QueueWait> waited = queueWait(shape, arrivals, meanHold, load);
		QueueWait wait = waited.orElse(NO_WAIT);
		return new Capacity(meanHold, slotShare, utilization, load, (largest != null) ? largest.getKey() : null,
				waited.isPresent(), wait.probability(), wait.moments());
	}

	/**
	 * The slot time the load takes per second: each kind of request's rate times how long
	 * it holds a slot, and a read-through cache's fills, each holding a slot for a read's
	 * work.
	 * @param shape how the part serves requests
	 * @param here the part's load
	 * @param hold how long a request of each kind holds a slot
	 * @return the busy milliseconds per second
	 */
	private static double busy(PartShape shape, NodeLoad here, Map<Op, @Nullable Moments> hold) {
		List<Double> held = new ArrayList<>();
		for (Op op : Op.values()) {
			Moments time = hold.get(op);
			if (op.rate(here) > 0 && time != null) {
				held.add(op.rate(here) * time.mean());
			}
		}
		if (shape.fills() > 0) {
			held.add(shape.fills() * shape.own(Op.READ).mean());
		}
		return FloatSum.of(held.stream().mapToDouble(Double::doubleValue));
	}

	/**
	 * The wait for a slot: an M/M/c queue per pool for a part whose requests queue, none
	 * for one that turns them away.
	 * @param shape how the part serves requests
	 * @param arrivals its requests and fills per second
	 * @param meanHold how long a request or a fill holds a slot
	 * @param load the largest share of a limit
	 * @return the wait; empty when the part cannot keep up (a share of 1 or more, or an
	 * unstable queue)
	 */
	private static Optional<QueueWait> queueWait(PartShape shape, double arrivals, double meanHold,
			@Nullable Double load) {
		if (load != null && load >= 1.0) {
			return Optional.empty();
		}
		int slots = slots(shape);
		if (!shape.queues() || slots == 0 || arrivals <= 0) {
			return Optional.of(NO_WAIT);
		}
		return QueueWait.mmc(slots, arrivals / shape.pools(), meanHold);
	}

	private static int slots(PartShape shape) {
		Integer slots = shape.slots();
		return (slots != null) ? slots : 0;
	}

	/**
	 * The part's estimate.
	 * @param node the part
	 * @param onPath whether it is on a client's request path
	 * @param here its load
	 * @param shape how it serves requests
	 * @return its estimate, figures rounded as the Python engine rounds them
	 */
	EstimatedPart estimate(NodeSpec node, boolean onPath, NodeLoad here, PartShape shape) {
		double total = here.total();
		double readMs = shape.own(Op.READ).mean();
		double writeMs = shape.own(Op.WRITE).mean();
		double service = (total > 0) ? (here.read() * readMs + here.write() * writeMs) / total : readMs;
		return new EstimatedPart(node.id(), node.name(), node.type(), onPath, Rounded.of(total, Rounded.RATE), status(),
				shape.slots(), shape.pools(), Rounded.of(service, Rounded.TIME),
				Rounded.of(this.meanHoldMs, Rounded.TIME), Rounded.of(this.utilization, Rounded.SHARE),
				Rounded.of(this.slotUtilization, Rounded.SHARE), Rounded.of(shape.cpu(), Rounded.SHARE),
				Rounded.of(shape.limit(), Rounded.SHARE), Rounded.of(this.load, Rounded.SHARE), this.limitedBy,
				this.stable ? Rounded.of(this.waitProbability, Rounded.SHARE) : null,
				this.stable ? Rounded.of(this.queueWait.mean(), Rounded.TIME) : null, null, null);
	}

	private PartStatus status() {
		if (!this.stable) {
			return PartStatus.OVERLOADED;
		}
		return (this.load != null && this.load >= BUSY) ? PartStatus.BUSY : PartStatus.OK;
	}

	private static @Nullable Double larger(@Nullable Double first, @Nullable Double second) {
		if (first == null || second == null) {
			return (first != null) ? first : second;
		}
		return Math.max(first, second);
	}

	private static void putIfKnown(Map<Limit, Double> shares, Limit limit, @Nullable Double share) {
		if (share != null) {
			shares.put(limit, share);
		}
	}

	/**
	 * The largest share, the first of equal ones, as Python's {@code max} picks it.
	 * @param shares the shares, in the order slots, CPU, rate limit
	 * @return the largest; {@code null} when there is none
	 */
	private static Map.@Nullable Entry<Limit, Double> largest(Map<Limit, Double> shares) {
		Map.Entry<Limit, Double> largest = null;
		for (Map.Entry<Limit, Double> share : shares.entrySet()) {
			if (largest == null || share.getValue() > largest.getValue()) {
				largest = share;
			}
		}
		return largest;
	}

}
