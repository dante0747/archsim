package io.archsim.model.validation;

import io.archsim.model.document.EdgeSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

/**
 * The design's nodes and valid links as a directed graph: its cycles, its topological
 * order, and how much work one request can cause along it.
 */
final class CallGraph {

	private final Set<String> nodes;

	private final Map<String, List<EdgeSpec>> out;

	/**
	 * @param nodeIds every node id, in the document's order
	 * @param out each node's valid outgoing links
	 */
	CallGraph(List<String> nodeIds, Map<String, List<EdgeSpec>> out) {
		this.nodes = new LinkedHashSet<>(nodeIds);
		this.out = out;
	}

	private List<EdgeSpec> out(String node) {
		return out.getOrDefault(node, List.of());
	}

	/** A node on the depth-first stack, and the next of its links to follow. */
	private static final class Frame {

		final String node;

		int next;

		Frame(String node) {
			this.node = node;
		}

	}

	private enum Color {

		ON_STACK, DONE

	}

	/**
	 * One directed cycle, as node ids with the first repeated at the end, found by a
	 * depth-first search from each node in order; empty when the graph is acyclic.
	 */
	Optional<List<String>> findCycle() {
		Map<String, Color> color = new HashMap<>(); // absent: not visited yet
		Map<String, String> parent = new HashMap<>();
		for (String root : nodes) {
			if (color.containsKey(root)) {
				continue;
			}
			Deque<Frame> stack = new ArrayDeque<>();
			stack.push(new Frame(root));
			color.put(root, Color.ON_STACK);
			while (!stack.isEmpty()) {
				Frame top = stack.element();
				List<EdgeSpec> edges = out(top.node);
				if (top.next >= edges.size()) {
					color.put(top.node, Color.DONE);
					stack.pop();
					continue;
				}
				String v = edges.get(top.next++).target();
				if (!nodes.contains(v)) {
					continue;
				}
				if (color.get(v) == Color.ON_STACK) {
					return Optional.of(cycleThrough(top.node, v, parent));
				}
				if (!color.containsKey(v)) {
					color.put(v, Color.ON_STACK);
					parent.put(v, top.node);
					stack.push(new Frame(v));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * The cycle closed by the link from {@code u} back to {@code v}, which is on the
	 * stack.
	 */
	private static List<String> cycleThrough(String u, String v, Map<String, String> parent) {
		List<String> cycle = new ArrayList<>();
		cycle.add(v);
		// every node on the stack but its root has a parent, and v is on the stack below
		// u
		for (String w = u; !w.equals(v); w = Objects.requireNonNull(parent.get(w))) {
			cycle.add(w);
		}
		cycle.add(v);
		Collections.reverse(cycle);
		return cycle;
	}

	/**
	 * The nodes in topological order (Kahn's algorithm); nodes on a cycle are left out.
	 */
	List<String> topologicalOrder() {
		Map<String, Integer> inDegree = new LinkedHashMap<>();
		nodes.forEach(n -> inDegree.put(n, 0));
		out.values().forEach(edges -> edges.forEach(e -> inDegree.computeIfPresent(e.target(), (k, d) -> d + 1)));
		Deque<String> queue = new ArrayDeque<>();
		inDegree.forEach((n, d) -> {
			if (d == 0) {
				queue.add(n);
			}
		});
		List<String> order = new ArrayList<>();
		while (!queue.isEmpty()) {
			String u = queue.remove();
			order.add(u);
			for (EdgeSpec e : out(u)) {
				if (inDegree.merge(e.target(), -1, Integer::sum) == 0) {
					queue.add(e.target());
				}
			}
		}
		return order;
	}

	/**
	 * What one call into a node costs in component calls, and the next node on its
	 * costliest path.
	 */
	private record Cost(double calls, @Nullable String next) {
	}

	/**
	 * The worst a client request can do: its component calls, and the path of nodes that
	 * drives them.
	 */
	record Amplification(double calls, List<String> path) {
	}

	/**
	 * The most component calls one client request can cause on an acyclic graph, and the
	 * path that drives them. One call into a part costs 1, plus: for a part that sends
	 * each call to one target ({@code sendsToOneTarget}: a load balancer, a queue), its
	 * costliest link; for any other part, which calls every dependency, the sum over its
	 * links. A link costs its attempts times a call at its target. Linear in the graph's
	 * size, however many paths it has.
	 * @param sources the client nodes
	 * @param clientAttempts the attempts each client makes per request
	 * @param sendsToOneTarget whether a node sends each call to one of its links
	 */
	Amplification amplification(List<String> sources, ToIntFunction<String> clientAttempts,
			Predicate<String> sendsToOneTarget) {
		Map<String, Cost> memo = new HashMap<>();
		double worst = 0.0;
		List<String> worstPath = List.of();
		for (String source : sources) {
			double total = clientAttempts.applyAsInt(source) * cost(source, memo, sendsToOneTarget).calls();
			if (total > worst) {
				worst = total;
				List<String> path = new ArrayList<>(List.of(source));
				String next = cost(source, memo, sendsToOneTarget).next();
				while (next != null && !path.contains(next) && path.size() < 50) {
					path.add(next);
					next = cost(next, memo, sendsToOneTarget).next();
				}
				worstPath = path;
			}
		}
		return new Amplification(worst, worstPath);
	}

	private Cost cost(String node, Map<String, Cost> memo, Predicate<String> sendsToOneTarget) {
		Cost known = memo.get(node);
		if (known != null) {
			return known;
		}
		double sum = 0.0;
		double most = Double.NEGATIVE_INFINITY;
		String costliest = null;
		for (EdgeSpec e : out(node)) {
			if (!nodes.contains(e.target())) {
				continue;
			}
			double calls = Math.max(1, e.config().maxAttempts()) * cost(e.target(), memo, sendsToOneTarget).calls();
			sum += calls;
			if (calls > most) {
				most = calls;
				costliest = e.target();
			}
		}
		Cost cost;
		if (costliest == null) {
			cost = new Cost(1.0, null);
		}
		else if (sendsToOneTarget.test(node)) {
			cost = new Cost(1.0 + most, costliest);
		}
		else {
			cost = new Cost(1.0 + sum, costliest);
		}
		memo.put(node, cost);
		return cost;
	}

}
