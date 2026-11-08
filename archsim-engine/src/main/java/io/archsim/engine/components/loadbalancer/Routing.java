package io.archsim.engine.components.loadbalancer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.loadbalancer.BalancingPolicy;
import io.archsim.model.document.RequestType;

/**
 * Which instance a load balancer sends a request to: every instance of every target
 * linked for the request's operation, in the links' order, picked in turn, by the fewest
 * requests held, or at random, avoiding the instance a retry follows. The candidates are
 * listed again whenever a target's instances change (an autoscaling target's), the turn
 * kept within the new list.
 */
final class Routing {

	private final SimContext context;

	private final String nodeId;

	private final BalancingPolicy policy;

	private final PythonRandom random;

	private final Map<RequestType, List<Candidate>> candidates = new EnumMap<>(RequestType.class);

	private final Map<RequestType, List<Integer>> versions = new EnumMap<>(RequestType.class);

	private final Map<RequestType, Integer> turns = new EnumMap<>(RequestType.class);

	/**
	 * A balancer's routing.
	 * @param context the run, with its parts and links
	 * @param nodeId the balancer
	 * @param policy how it picks
	 * @param random the stream a random pick draws from
	 */
	Routing(SimContext context, String nodeId, BalancingPolicy policy, PythonRandom random) {
		this.context = context;
		this.nodeId = nodeId;
		this.policy = policy;
		this.random = random;
	}

	/**
	 * The instance a request goes to.
	 * @param operation the request's operation
	 * @param avoid the instance a retry avoids, when there is another; {@code null} for
	 * none
	 * @return the instance; empty when no target has an instance taking requests (a
	 * Kubernetes service whose pods are all Pending or starting)
	 */
	Optional<Candidate> pick(RequestType operation, @Nullable Candidate avoid) {
		List<Candidate> listed = candidates(operation);
		int count = listed.size();
		if (count == 0) {
			return Optional.empty();
		}
		if (this.policy == BalancingPolicy.RANDOM) {
			int index = this.random.below(count);
			return Optional
				.of(avoided(listed.get(index), avoid, count) ? listed.get((index + 1) % count) : listed.get(index));
		}
		int start = this.turns.getOrDefault(operation, 0);
		this.turns.put(operation, (start + 1) % count);
		if (this.policy == BalancingPolicy.LEAST_CONNECTIONS) {
			return Optional.of(leastLoaded(listed, start, avoid));
		}
		Candidate turn = listed.get(start);
		return Optional.of(avoided(turn, avoid, count) ? listed.get((start + 1) % count) : turn);
	}

	// in rotation order from the turn's, the first idle instance, else the least loaded
	// (loads are never below zero, so a pool of idle replicas is not scanned in full)
	private static Candidate leastLoaded(List<Candidate> listed, int start, @Nullable Candidate avoid) {
		int count = listed.size();
		Candidate best = null;
		long bestLoad = 0;
		for (int step = 0; step < count; step++) {
			Candidate candidate = listed.get((start + step) % count);
			if (avoided(candidate, avoid, count)) {
				continue;
			}
			long load = candidate.target().instanceLoad(candidate.index());
			if (load <= 0) {
				return candidate;
			}
			if (best == null || load < bestLoad) {
				best = candidate;
				bestLoad = load;
			}
		}
		if (best == null) {
			throw new IllegalStateException("a balancer with two candidates or more avoids only one");
		}
		return best;
	}

	private static boolean avoided(Candidate candidate, @Nullable Candidate avoid, int count) {
		return avoid != null && count > 1 && candidate.edge().id().equals(avoid.edge().id())
				&& candidate.index() == avoid.index();
	}

	private List<Candidate> candidates(RequestType operation) {
		List<Integer> current = this.context.outEdges(this.nodeId, "out")
			.stream()
			.filter((edge) -> edge.carries(operation))
			.map((edge) -> this.context.component(edge.targetId()).instancesVersion())
			.toList();
		List<Candidate> listed = this.candidates.get(operation);
		if (listed != null && current.equals(this.versions.get(operation))) {
			return listed;
		}
		List<Candidate> fresh = list(operation);
		this.candidates.put(operation, fresh);
		this.versions.put(operation, current);
		this.turns.put(operation, this.turns.getOrDefault(operation, 0) % Math.max(1, fresh.size()));
		return fresh;
	}

	private List<Candidate> list(RequestType operation) {
		List<Candidate> listed = new ArrayList<>();
		for (EdgeRuntime edge : this.context.outEdges(this.nodeId, "out")) {
			if (edge.carries(operation)) {
				RuntimeComponent target = this.context.component(edge.targetId());
				for (int index = 0; index < target.instanceCount(); index++) {
					listed.add(new Candidate(edge, target, index));
				}
			}
		}
		return List.copyOf(listed);
	}

	/**
	 * An instance a balancer may send a request to.
	 *
	 * @param edge the link to its part
	 * @param target its part
	 * @param index the instance, from 0
	 */
	record Candidate(EdgeRuntime edge, RuntimeComponent target, int index) {
	}

}
