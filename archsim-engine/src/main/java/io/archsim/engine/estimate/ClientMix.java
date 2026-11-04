package io.archsim.engine.estimate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.queueing.Moments;
import io.archsim.engine.queueing.WeightedTime;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * The clients' request paths, each request type's time summed along its path and mixed by
 * traffic share, and where an average request's time goes.
 *
 * @param times each client's requests of each kind, weighted by their rate
 * @param paths each client's requests of each kind, with their mean latency
 * @param complete whether every path could be estimated
 * @param totalRps the clients' requests per second over every path
 * @param breakdown where an average request's time goes, the largest first; empty when
 * the latency is not estimated
 * @param perRequestMs each part's wait and own time per client request, rounded, by its
 * id, in the order the parts were estimated; empty when the latency is not estimated
 */
record ClientMix(List<WeightedTime> times, List<ClientPath> paths, boolean complete, double totalRps,
		List<TimeShare> breakdown, Map<String, @Nullable Double> perRequestMs) {

	/** What the breakdown calls the time on the links. */
	private static final String NETWORK = "Network";

	/**
	 * The clients' paths, from the parts' estimates.
	 * @param graph the design
	 * @param loads the parts' and links' loads
	 * @param parts the parts' estimates and times
	 * @param path the parts and links on a client's request path
	 * @return the mix
	 */
	static ClientMix of(EstimateGraph graph, LoadEstimate loads, PartEstimates parts, EstimateGraph.RequestPath path) {
		return new Mixer(graph, loads, parts, path).mix();
	}

	private static double meanMs(TimeShare share) {
		Double ms = share.meanMs();
		return (ms != null) ? ms : 0.0;
	}

	/** One mix being put together. */
	private static final class Mixer {

		private final EstimateGraph graph;

		private final LoadEstimate loads;

		private final PartEstimates parts;

		private final EstimateGraph.RequestPath path;

		private final List<WeightedTime> times = new ArrayList<>();

		private final List<ClientPath> paths = new ArrayList<>();

		private final Map<String, @Nullable Double> perRequestMs = new LinkedHashMap<>();

		private boolean complete = true;

		Mixer(EstimateGraph graph, LoadEstimate loads, PartEstimates parts, EstimateGraph.RequestPath path) {
			this.graph = graph;
			this.loads = loads;
			this.parts = parts;
			this.path = path;
		}

		ClientMix mix() {
			for (NodeSpec node : this.graph.architecture().nodes()) {
				if (this.graph.isClient(node.id())) {
					this.loads.node(node.id())
						.filter((here) -> here.total() > 0)
						.ifPresent((here) -> addPaths(node.id(), here));
				}
			}
			double total = FloatSum.of(this.times.stream().mapToDouble(WeightedTime::weight));
			List<TimeShare> breakdown = (!this.times.isEmpty() && this.complete && total > 0) ? breakdown(total)
					: List.of();
			return new ClientMix(List.copyOf(this.times), List.copyOf(this.paths), this.complete, total, breakdown,
					Collections.unmodifiableMap(this.perRequestMs));
		}

		/**
		 * A client's requests of each kind, summed along their paths.
		 * @param client the client's id
		 * @param here its load
		 */
		private void addPaths(String client, NodeLoad here) {
			for (Op op : Op.values()) {
				double rate = op.rate(here);
				if (rate <= 0) {
					continue;
				}
				Moments time = this.parts.calls(client, op, here);
				if (time == null) {
					this.complete = false;
				}
				else {
					this.times.add(new WeightedTime(rate, time));
					this.paths.add(new ClientPath(client, this.graph.label(client), op, Rounded.of(rate, Rounded.RATE),
							Rounded.of(time.mean(), Rounded.TIME)));
				}
			}
		}

		/**
		 * Where an average request's time goes: each part's wait and own time on the
		 * path, and the network both ways on the links the clients' requests take.
		 * @param totalRps the clients' requests per second
		 * @return the shares, the largest first
		 */
		private List<TimeShare> breakdown(double totalRps) {
			List<TimeShare> shares = new ArrayList<>();
			for (Map.Entry<String, Map<Op, Moments>> own : this.parts.ownTimes().entrySet()) {
				NodeLoad here = this.loads.node(own.getKey()).orElse(null);
				if (!this.path.parts().contains(own.getKey()) || here == null) {
					continue;
				}
				Double ms = Rounded.of(
						(here.read() * own.getValue().getOrDefault(Op.READ, Moments.ZERO).mean()
								+ here.write() * own.getValue().getOrDefault(Op.WRITE, Moments.ZERO).mean()) / totalRps,
						Rounded.TIME);
				this.perRequestMs.put(own.getKey(), ms);
				shares.add(new TimeShare(own.getKey(), this.graph.label(own.getKey()), ms, null));
			}
			shares.add(new TimeShare(null, NETWORK, Rounded.of(network(totalRps), Rounded.TIME), null));
			double grand = FloatSum.of(shares.stream().mapToDouble(ClientMix::meanMs));
			double whole = (grand != 0) ? grand : 1.0;
			return shares.stream()
				.map((share) -> new TimeShare(share.nodeId(), share.label(), share.meanMs(),
						Rounded.of(meanMs(share) / whole, Rounded.SHARE)))
				.sorted(Comparator.comparingDouble((TimeShare share) -> -meanMs(share)))
				.toList();
		}

		/**
		 * The network's time per request: each link on the path, both ways, by its share
		 * of the requests.
		 * @param totalRps the clients' requests per second
		 * @return the time, in milliseconds
		 */
		private double network(double totalRps) {
			double network = 0.0;
			for (EdgeSpec edge : this.graph.architecture().edges()) {
				NodeLoad sent = this.loads.edges().get(edge.id());
				if (this.path.links().contains(edge.id()) && sent != null) {
					network += sent.total() * this.parts.link(edge.id()).mean() / totalRps;
				}
			}
			return network;
		}

	}

}
