package io.archsim.model.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
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

import io.archsim.model.document.EdgeSpec;

/**
 * The design's nodes and valid links as a directed graph: its cycles, its topological
 * order, and how much work one request can cause along it.
 */
final class CallGraph {

	/** The most nodes the path of an amplification lists. */
	private static final int MAX_PATH_LENGTH = 50;

	private final Set<String> nodes;

	private final Map<String, List<EdgeSpec>> out;

	/**
	 * The graph of these nodes and links.
	 * @param nodeIds every node id, in the document's order
	 * @param out each node's valid outgoing links
	 */
	CallGraph(List<String> nodeIds, Map<String, List<EdgeSpec>> out) {
		this.nodes = new LinkedHashSet<>(nodeIds);
		this.out = out;
	}

	private List<EdgeSpec> out(String node) {
		return this.out.getOrDefault(node, List.of());
	}

	/**
	 * One directed cycle, found by a depth-first search from each node in order.
	 * @return the cycle as node ids with the first repeated at the end; empty when the
	 * graph is acyclic
	 */
	Optional<List<String>> findCycle() {
		Map<String, Color> colors = new HashMap<>(); // absent: not visited yet
		Map<String, String> parents = new HashMap<>();
		for (String root : this.nodes) {
			if (colors.containsKey(root)) {
				continue;
			}
			Optional<List<String>> cycle = findCycleFrom(root, colors, parents);
			if (cycle.isPresent()) {
				return cycle;
			}
		}
		return Optional.empty();
	}

	/**
	 * Searches depth first from a node no search has visited yet.
	 * @param root the node to start from
	 * @param colors the nodes visited so far: on the stack, or done
	 * @param parents the node each visited node was reached from
	 * @return the first cycle found, or empty when none is reachable from the root
	 */
	private Optional<List<String>> findCycleFrom(String root, Map<String, Color> colors, Map<String, String> parents) {
		Deque<Frame> stack = new ArrayDeque<>();
		stack.push(new Frame(root, out(root)));
		colors.put(root, Color.ON_STACK);
		while (!stack.isEmpty()) {
			Frame top = stack.element();
			if (!top.hasLinkToFollow()) {
				colors.put(top.node(), Color.DONE);
				stack.pop();
				continue;
			}
			String target = top.nextLink().target();
			if (!this.nodes.contains(target)) {
				continue;
			}
			if (colors.get(target) == Color.ON_STACK) {
				return Optional.of(cycleThrough(top.node(), target, parents));
			}
			if (!colors.containsKey(target)) {
				colors.put(target, Color.ON_STACK);
				parents.put(target, top.node());
				stack.push(new Frame(target, out(target)));
			}
		}
		return Optional.empty();
	}

	/**
	 * The cycle closed by a link back to a node on the depth-first stack.
	 * @param from the node the link leaves, at the top of the stack
	 * @param onStack the node the link reaches, lower on the stack
	 * @param parents the node each visited node was reached from
	 * @return the cycle, starting and ending at {@code onStack}
	 */
	private static List<String> cycleThrough(String from, String onStack, Map<String, String> parents) {
		List<String> cycle = new ArrayList<>();
		cycle.add(onStack);
		// every node on the stack but its root has a parent, and onStack is on the stack
		// below from
		for (String node = from; !node.equals(onStack); node = Objects.requireNonNull(parents.get(node))) {
			cycle.add(node);
		}
		cycle.add(onStack);
		Collections.reverse(cycle);
		return cycle;
	}

	/**
	 * The nodes in topological order (Kahn's algorithm).
	 * @return the node ids, each after every node that links to it; nodes on a cycle are
	 * left out
	 */
	List<String> topologicalOrder() {
		Map<String, Integer> inDegree = inDegrees();
		Deque<String> queue = new ArrayDeque<>();
		inDegree.forEach((node, degree) -> {
			if (degree == 0) {
				queue.add(node);
			}
		});
		List<String> order = new ArrayList<>();
		while (!queue.isEmpty()) {
			String next = queue.remove();
			order.add(next);
			for (EdgeSpec edge : out(next)) {
				if (inDegree.merge(edge.target(), -1, Integer::sum) == 0) {
					queue.add(edge.target());
				}
			}
		}
		return order;
	}

	/**
	 * How many links reach each node.
	 * @return each node's count of incoming links, in the nodes' order
	 */
	private Map<String, Integer> inDegrees() {
		Map<String, Integer> inDegree = new LinkedHashMap<>();
		this.nodes.forEach((node) -> inDegree.put(node, 0));
		for (List<EdgeSpec> edges : this.out.values()) {
			edges.forEach((edge) -> inDegree.computeIfPresent(edge.target(), (target, degree) -> degree + 1));
		}
		return inDegree;
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
	 * @return the worst a client request can do
	 */
	Amplification amplification(List<String> sources, ToIntFunction<String> clientAttempts,
			Predicate<String> sendsToOneTarget) {
		Costs costs = new Costs(sendsToOneTarget);
		double worst = 0.0;
		List<String> worstPath = List.of();
		for (String source : sources) {
			double total = clientAttempts.applyAsInt(source) * costs.of(source).calls();
			if (total > worst) {
				worst = total;
				worstPath = costs.costliestPath(source);
			}
		}
		return new Amplification(worst, worstPath);
	}

	/**
	 * The worst a client request can do: its component calls, and the path of nodes that
	 * drives them.
	 *
	 * @param calls the component calls one client request can cause
	 * @param path the node ids from the client along the costliest links
	 */
	record Amplification(double calls, List<String> path) {
	}

	/** Whether the depth-first search is still below a node, or done with it. */
	private enum Color {

		/** The node is on the stack: the search is following its links. */
		ON_STACK,

		/** The search has followed every link from the node. */
		DONE

	}

	/** A node on the depth-first stack, and the links from it still to follow. */
	private static final class Frame {

		private final String node;

		private final Iterator<EdgeSpec> links;

		/**
		 * A node the search has just reached, with none of its links followed.
		 * @param node the node's id
		 * @param links its outgoing links
		 */
		Frame(String node, List<EdgeSpec> links) {
			this.node = node;
			this.links = links.iterator();
		}

		String node() {
			return this.node;
		}

		boolean hasLinkToFollow() {
			return this.links.hasNext();
		}

		/**
		 * The next link to follow, which is then followed.
		 * @return the first of the node's links not followed yet
		 */
		EdgeSpec nextLink() {
			return this.links.next();
		}

	}

	/**
	 * What one call into a node costs in component calls, and the next node on its
	 * costliest path.
	 *
	 * @param calls the component calls one call into the node causes, itself included
	 * @param next the target of its costliest link; {@code null} when it has none
	 */
	private record Cost(double calls, @Nullable String next) {
	}

	/** The cost of a call into each node, worked out once per node. */
	private final class Costs {

		private final Map<String, Cost> memo = new HashMap<>();

		private final Predicate<String> sendsToOneTarget;

		Costs(Predicate<String> sendsToOneTarget) {
			this.sendsToOneTarget = sendsToOneTarget;
		}

		Cost of(String node) {
			Cost known = this.memo.get(node);
			if (known != null) {
				return known;
			}
			Cost cost = costOfLinks(node);
			this.memo.put(node, cost);
			return cost;
		}

		/**
		 * The cost of a call into a node, from the cost of a call along each of its
		 * links.
		 * @param node the node's id
		 * @return 1 plus its costliest link, or the sum of its links, as the node sends
		 * each call to one link or to all
		 */
		private Cost costOfLinks(String node) {
			double sum = 0.0;
			double most = Double.NEGATIVE_INFINITY;
			String costliest = null;
			for (EdgeSpec edge : out(node)) {
				if (!CallGraph.this.nodes.contains(edge.target())) {
					continue;
				}
				int attempts = Math.max(1, edge.config().maxAttempts());
				double calls = attempts * of(edge.target()).calls();
				sum += calls;
				if (calls > most) {
					most = calls;
					costliest = edge.target();
				}
			}
			if (costliest == null) {
				return new Cost(1.0, null);
			}
			return new Cost(1.0 + (this.sendsToOneTarget.test(node) ? most : sum), costliest);
		}

		/**
		 * The path a client request takes along each node's costliest link.
		 * @param source the client's node id
		 * @return the node ids from the client, stopping at a node with no link, at a
		 * node already on the path, or at {@code MAX_PATH_LENGTH} nodes
		 */
		List<String> costliestPath(String source) {
			List<String> path = new ArrayList<>(List.of(source));
			String next = of(source).next();
			while (next != null && !path.contains(next) && path.size() < MAX_PATH_LENGTH) {
				path.add(next);
				next = of(next).next();
			}
			return path;
		}

	}

}
