package io.archsim.engine.components.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;

/**
 * A service's replicas during a run: every replica started, and those that take requests.
 * The set of an autoscaling service, or of a service on a Kubernetes cluster, changes
 * ({@link Scaling}): a replica started joins it, the newest leaves it to scale down (it
 * drains what it holds), a replica whose pod is Pending leaves it until the pod is ready,
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
	 * Replicas that change during the run, an autoscaling service's or a Kubernetes
	 * service's, their gauges at the replicas present.
	 * @param timeline the run's timeline
	 * @param perReplica the handlers of each replica
	 * @param replicas the replicas present at the start of the run
	 * @return the set
	 */
	static ReplicaSet changing(Timeline timeline, int perReplica, List<Replica> replicas) {
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
	 * The replicas taking requests now.
	 * @return a copy, in the order they take turns
	 */
	List<Replica> routableReplicas() {
		return this.routable.stream().map(this.started::get).toList();
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
	 * How the replicas change.
	 * @return their scaling; empty for fixed replicas
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
	 * Replicas that change: their gauges, the replicas on their way, and those started,
	 * retired, withdrawn and rejoining.
	 */
	final class Scaling {

		private final Gauge capacity;

		private final Gauge active;

		private final int perReplica;

		private final Set<Integer> withdrawn = new HashSet<>();

		private int starting;

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
		 * The replicas on their way: asked for, or whose pod is Pending or starting.
		 * @return how many
		 */
		int starting() {
			return this.starting;
		}

		/**
		 * Counts a replica asked for, on its way until it starts.
		 */
		void expect() {
			this.starting++;
		}

		/**
		 * Counts a replica no longer on its way: its pod was deleted while Pending.
		 */
		void abandon() {
			this.starting--;
		}

		/**
		 * Adds a replica asked for during the run, which takes requests from now.
		 * @param replica the replica
		 * @param now the time
		 */
		void start(Replica replica, double now) {
			this.starting--;
			addRoutable(replica);
			changeBy(1, now);
		}

		/**
		 * Stops sending requests to a replica whose pod is Pending, on its way again
		 * until it rejoins.
		 * @param replica the replica
		 * @param now the time
		 * @throws IllegalStateException when the replica takes no requests
		 */
		void withdraw(Replica replica, double now) {
			int index = ReplicaSet.this.started.indexOf(replica);
			if (!ReplicaSet.this.routable.remove(Integer.valueOf(index))) {
				throw new IllegalStateException("a replica that takes no requests cannot be withdrawn");
			}
			this.withdrawn.add(index);
			this.starting++;
			changeBy(-1, now);
		}

		/**
		 * Sends requests again to a replica whose pod is ready, after the others.
		 * @param replica the replica
		 * @param now the time
		 * @throws IllegalStateException when the replica was not withdrawn
		 */
		void rejoin(Replica replica, double now) {
			int index = ReplicaSet.this.started.indexOf(replica);
			if (!this.withdrawn.remove(index)) {
				throw new IllegalStateException("only a withdrawn replica can rejoin");
			}
			this.starting--;
			ReplicaSet.this.routable.add(index);
			changeBy(1, now);
		}

		/**
		 * Stops sending requests to the newest replica, unless it is the last; it drains
		 * what it holds.
		 * @param now the time
		 * @return the replica retired; none when it was the last
		 */
		Optional<Replica> retireNewest(double now) {
			if (ReplicaSet.this.routable.size() <= 1) {
				return Optional.empty();
			}
			int index = ReplicaSet.this.routable.removeLast();
			changeBy(-1, now);
			return Optional.of(ReplicaSet.this.started.get(index));
		}

		/**
		 * The most replicas that took requests at once.
		 * @return the peak
		 */
		long peak() {
			return this.active.runPeak();
		}

		/**
		 * The replicas taking requests in each bucket, over the part of the bucket the
		 * run simulated, so the last bucket, cut short, does not look like a drop.
		 * @return one mean per bucket; {@code null} for a bucket the run never reached
		 */
		List<@Nullable Double> countSeries() {
			return this.active.meanSeriesUntilEnd();
		}

		// the replicas taking requests rose or fell: a new version
		private void changeBy(int replicas, double now) {
			this.capacity.add(now, (long) replicas * this.perReplica);
			this.active.add(now, replicas);
			ReplicaSet.this.version++;
		}

		private void finish(double endMs) {
			this.capacity.finish(endMs);
			this.active.finish(endMs);
		}

	}

}
