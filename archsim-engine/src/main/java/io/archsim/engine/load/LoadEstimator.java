package io.archsim.engine.load;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

/**
 * The load each part of a design carries, estimated without simulating it: the clients'
 * requests split by their traffic shares, then passed down the links in the order the
 * requests flow (parts on a cycle keep whatever reached them). A load balancer or queue
 * splits each request type over the links that carry it, by the replicas behind them; a
 * cache passes on its misses; any other part sends each request type to every link that
 * carries it, and cache-aside lookups add a write-back per miss. A part whose load
 * depends on the run (a TTL cache's misses, unless estimated) has no load, and neither
 * does what it sends.
 */
public final class LoadEstimator {

	private final ComponentRegistry registry;

	/**
	 * An estimator for designs of the given component types.
	 * @param registry the component types
	 */
	public LoadEstimator(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * The load of every part, and of every link a load crosses. A part's settings are
	 * read leniently: a setting a valid config leaves out counts at its type's default.
	 * @param architecture the design's parts and links
	 * @param workload the design's workload, for its read ratio
	 * @param rate the clients' arrival rate, in requests per second: the workload's peak,
	 * or another rate
	 * @param misses the share of reads each TTL cache misses, where known
	 * @return the loads
	 */
	public LoadEstimate estimate(Architecture architecture, Workload workload, double rate, CacheMisses misses) {
		Flow flow = new Flow(new DesignGraph(architecture, this.registry), misses);
		flow.startAtClients(architecture.nodes(), rate, Math.min(1.0, Math.max(0.0, workload.readRatio())));
		flow.passDown(architecture.nodes());
		return new LoadEstimate(flow.loads, flow.edgeLoads);
	}

	/**
	 * The share of reads each TTL cache misses, estimated from the reads and writes that
	 * reach it, its TTL, the key popularity and the keys its memory holds. A cache whose
	 * load depends on another TTL cache upstream is estimated after it; one whose load
	 * cannot be estimated at all is left out.
	 * @param architecture the design's parts and links
	 * @param workload the design's workload: its read ratio, key count and skew
	 * @param rate the clients' arrival rate, in requests per second
	 * @return the share each cache misses, by its id, in the order they were estimated
	 */
	public Map<String, Double> ttlMissShares(Architecture architecture, Workload workload, double rate) {
		return new TtlMissShares(this, this.registry).of(architecture, workload, rate);
	}

	private static boolean carries(EdgeSpec edge, RequestType type) {
		return edge.config().carries(type);
	}

	/** One estimate: the loads so far, passed down the graph. */
	private final class Flow {

		private final DesignGraph graph;

		private final CacheMisses misses;

		private final Map<String, @Nullable NodeLoad> loads = new LinkedHashMap<>();

		private final Map<String, @Nullable NodeLoad> edgeLoads = new LinkedHashMap<>();

		Flow(DesignGraph graph, CacheMisses misses) {
			this.graph = graph;
			this.misses = misses;
		}

		void startAtClients(List<NodeSpec> nodes, double rate, double readRatio) {
			nodes.forEach((node) -> this.loads.put(node.id(), NodeLoad.NONE));
			ClientShares.of(nodes, LoadEstimator.this.registry)
				.forEach((id, fraction) -> this.loads.put(id,
						new NodeLoad(rate * fraction * readRatio, rate * fraction * (1 - readRatio))));
		}

		/**
		 * Passes the loads down the links in Kahn's order: a part is passed on once every
		 * link into it has been.
		 * @param nodes the design's parts, in its order
		 */
		void passDown(List<NodeSpec> nodes) {
			Map<String, Integer> waiting = this.graph.indegrees();
			Deque<String> ready = new ArrayDeque<>();
			nodes.stream().map(NodeSpec::id).filter((id) -> waiting.getOrDefault(id, 0) == 0).forEach(ready::addLast);
			while (!ready.isEmpty()) {
				String id = ready.removeFirst();
				NodeLoad here = this.loads.get(id);
				for (EdgeSpec edge : this.graph.outs(id)) {
					NodeLoad sent = (here != null) ? sent(id, here, edge) : null;
					add(edge.target(), sent);
					this.edgeLoads.put(edge.id(), sent);
					int left = waiting.getOrDefault(edge.target(), 1) - 1;
					waiting.put(edge.target(), left);
					if (left == 0) {
						ready.addLast(edge.target());
					}
				}
			}
		}

		private @Nullable NodeLoad sent(String id, NodeLoad here, EdgeSpec edge) {
			return switch (this.graph.family(id)) {
				case NETWORK, MESSAGING -> new NodeLoad(here.read() * split(id, edge, RequestType.READ),
						here.write() * split(id, edge, RequestType.WRITE));
				case CACHE -> fromCache(id, here, edge);
				case TRAFFIC, COMPUTE, DATABASE, EXTERNAL, OTHER -> fromCaller(id, here, edge);
			};
		}

		/**
		 * A balancer's or queue's share of a request type sent over one of its links: by
		 * the replicas behind each link that carries the type.
		 * @param id the balancer's or queue's id
		 * @param edge the link
		 * @param type the request type
		 * @return the link's share
		 */
		private double split(String id, EdgeSpec edge, RequestType type) {
			List<EdgeSpec> carrying = this.graph.outs(id).stream().filter((out) -> carries(out, type)).toList();
			double total = this.graph.weights(carrying);
			if (!carries(edge, type) || total <= 0) {
				return 0.0;
			}
			return this.graph.weight(edge.target()) / total;
		}

		private @Nullable NodeLoad fromCache(String id, NodeLoad here, EdgeSpec edge) {
			if (!edge.sourcePort().equals(DesignGraph.MISS_PORT)) {
				return NodeLoad.NONE;
			}
			Double missed = missShare(id);
			if (missed == null) {
				return null;
			}
			return new NodeLoad(carries(edge, RequestType.READ) ? here.read() * missed : 0.0, 0.0);
		}

		private @Nullable NodeLoad fromCaller(String id, NodeLoad here, EdgeSpec edge) {
			double carried = carries(edge, RequestType.READ) ? here.read() : 0.0;
			Double read = carried;
			if (carried != 0 && this.graph.family(id) == ComponentFamily.COMPUTE) {
				read = computeReads(id, carried, edge);
			}
			double write = carries(edge, RequestType.WRITE) ? here.write() : 0.0;
			return (read != null) ? new NodeLoad(read, write) : null;
		}

		/**
		 * A service's reads over a link: the share that missed its cache-aside lookups
		 * over a link that reads only on a miss, and a write-back per miss over a lookup.
		 * @param id the service's id
		 * @param read the reads per second it carries
		 * @param edge the link
		 * @return the reads per second over the link; {@code null} when they depend on
		 * the run
		 */
		private @Nullable Double computeReads(String id, double read, EdgeSpec edge) {
			List<EdgeSpec> outs = this.graph.outs(id);
			if (edge.config().readsOnMiss()) {
				Double fetched = fetchShare(outs);
				return (fetched != null) ? read * fetched : null;
			}
			if (this.graph.asideLookups(outs).contains(edge)
					&& this.graph.settings(edge.target()).truthy("populateOnMiss", true)) {
				Double missed = missShare(edge.target());
				return (missed != null) ? read * (1 + missed) : read;
			}
			return read;
		}

		/**
		 * The share of a service's reads that miss at least one of its cache-aside
		 * lookups.
		 * @param outs the service's links
		 * @return the share; {@code null} when a lookup's misses depend on the run
		 */
		private @Nullable Double fetchShare(List<EdgeSpec> outs) {
			List<EdgeSpec> lookups = this.graph.asideLookups(outs);
			double hitAll = 1.0;
			for (EdgeSpec lookup : lookups) {
				Double missed = missShare(lookup.target());
				if (missed == null) {
					return null;
				}
				hitAll *= 1 - missed;
			}
			return lookups.isEmpty() ? 0.0 : 1 - hitAll;
		}

		/**
		 * The share of reads a cache misses: one less its hit ratio, else its TTL miss
		 * share.
		 * @param cacheId the cache's id
		 * @return the share; {@code null} when it depends on the run
		 */
		private @Nullable Double missShare(String cacheId) {
			LenientSettings cache = this.graph.settings(cacheId);
			// an empty hit model is the default, a fixed hit ratio
			if (!cache.truthy("hitModel", false) || cache.is("hitModel", "fixed_ratio")) {
				return 1 - cache.number("hitRatio").orElse(0.0);
			}
			return this.misses.of(cacheId);
		}

		private void add(String id, @Nullable NodeLoad sent) {
			NodeLoad current = this.loads.get(id);
			if (current != null) {
				this.loads.put(id, (sent != null) ? current.plus(sent) : null);
			}
		}

	}

}
