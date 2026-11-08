package io.archsim.engine.components.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;

/**
 * A service's replicas during a run: every replica started, and those that take requests,
 * in the order they started. An autoscaling service's set changes ({@link Scaling}): a
 * replica started joins it, the newest leaves it to scale down (it drains what it holds),
 * and its gauges follow the replicas and the handlers taking requests. Each change is a
 * new version, which tells a load balancer to list the service's instances again.
 */
final class ReplicaSet {

	private final List<Replica> started = new ArrayList<>();

	private final List<Integer> routable = new ArrayList<>();

	private final @Nullable Scaling scaling;

	private int version;

	private ReplicaSet(List<Replica> present, @Nullable Timeline timeline, int perReplica) {
		present.forEach(this::addRoutable);
		this.scaling = (timeline != null) ? new Scaling(timeline, perReplica) : null;
	}

	/**
	 * A service's fixed replicas.
	 * @param replicas the replicas, present at the start of the run
	 * @return the set
	 */
	static ReplicaSet fixed(List<Replica> replicas) {
		return new ReplicaSet(replicas, null, 0);
	}

	/**
	 * An autoscaling service's replicas, its gauges at the replicas present.
	 * @param timeline the run's timeline
	 * @param perReplica the handlers of each replica
	 * @param replicas the replicas present at the start of the run
	 * @return the set
	 */
	static ReplicaSet autoscaling(Timeline timeline, int perReplica, List<Replica> replicas) {
		return new ReplicaSet(replicas, timeline, perReplica);
	}

	/**
	 * The replica a turn lands on, among those taking requests.
	 * @param turn the turn, from 0
	 * @return the replica
	 */
	Replica routed(int turn) {
		return this.started.get(this.routable.get(turn % this.routable.size()));
	}

	/**
	 * The replicas taking requests.
	 * @return how many
	 */
	int routableCount() {
		return this.routable.size();
	}

	/**
	 * The first replica started, whose CPU every replica's is like.
	 * @return the replica
	 */
	Replica first() {
		return this.started.getFirst();
	}

	/**
	 * How often the replicas taking requests changed.
	 * @return the version, 0 for a set that never changed
	 */
	int version() {
		return this.version;
	}

	/**
	 * How an autoscaling service's replicas change.
	 * @return its scaling; empty for fixed replicas
	 */
	Optional<Scaling> scaling() {
		return Optional.ofNullable(this.scaling);
	}

	/**
	 * The replicas taking requests, on average over the measurement window.
	 * @return the replicas
	 */
	double mean() {
		return (this.scaling != null) ? this.scaling.active.windowMean() : this.routable.size();
	}

	/**
	 * The replicas taking requests in each bucket, on average.
	 * @param buckets the timeline's buckets
	 * @return one mean per bucket
	 */
	double[] meanSeries(int buckets) {
		if (this.scaling != null) {
			return this.scaling.active.meanSeries();
		}
		double[] constant = new double[buckets];
		Arrays.fill(constant, this.routable.size());
		return constant;
	}

	/**
	 * Ends the gauges' series at the end of the run.
	 * @param endMs when the run stopped
	 */
	void finish(double endMs) {
		if (this.scaling != null) {
			this.scaling.finish(endMs);
		}
	}

	private void addRoutable(Replica replica) {
		this.started.add(replica);
		this.routable.add(this.started.size() - 1);
	}

	/**
	 * An autoscaling service's changing replicas: their gauges, and the replicas started
	 * and retired.
	 */
	final class Scaling {

		private final Gauge capacity;

		private final Gauge active;

		private final int perReplica;

		private double end;

		private Scaling(Timeline timeline, int perReplica) {
			this.capacity = new Gauge(timeline);
			this.active = new Gauge(timeline);
			this.perReplica = perReplica;
			this.capacity.add(0.0, (long) ReplicaSet.this.routable.size() * perReplica);
			this.active.add(0.0, ReplicaSet.this.routable.size());
		}

		/**
		 * The handlers taking requests over time.
		 * @return the gauge
		 */
		Gauge capacity() {
			return this.capacity;
		}

		/**
		 * Adds a replica started during the run, which takes requests from now.
		 * @param replica the replica
		 * @param now the time
		 */
		void start(Replica replica, double now) {
			addRoutable(replica);
			this.capacity.add(now, this.perReplica);
			this.active.add(now, 1);
			ReplicaSet.this.version++;
		}

		/**
		 * Stops sending requests to the newest replica, unless it is the last; it drains
		 * what it holds.
		 * @param now the time
		 */
		void retireNewest(double now) {
			if (ReplicaSet.this.routable.size() <= 1) {
				return;
			}
			ReplicaSet.this.routable.removeLast();
			this.capacity.add(now, -this.perReplica);
			this.active.add(now, -1);
			ReplicaSet.this.version++;
		}

		/**
		 * The most replicas that took requests at once.
		 * @return the peak
		 */
		long peak() {
			return Arrays.stream(this.active.maxSeries()).max().orElse(ReplicaSet.this.routable.size());
		}

		/**
		 * The replicas taking requests in each bucket, over the part of the bucket the
		 * run simulated, so the last bucket, cut short, does not look like a drop.
		 * @param timeline the run's timeline
		 * @return one mean per bucket; {@code null} for a bucket the run never reached
		 */
		List<@Nullable Double> countSeries(Timeline timeline) {
			double[] area = this.active.area();
			List<@Nullable Double> series = new ArrayList<>(area.length);
			for (int bucket = 0; bucket < area.length; bucket++) {
				double covered = Math.min(timeline.bucketMs(), this.end - (double) bucket * timeline.bucketMs());
				series.add((covered > 0) ? area[bucket] / covered : null);
			}
			return series;
		}

		private void finish(double endMs) {
			this.end = endMs;
			this.capacity.finish(endMs);
			this.active.finish(endMs);
		}

	}

}
