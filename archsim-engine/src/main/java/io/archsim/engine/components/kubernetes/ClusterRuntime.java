package io.archsim.engine.components.kubernetes;

import java.util.List;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.kernel.Promise;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.kubernetes.PodRequest;
import io.archsim.model.document.NodeSpec;

/**
 * A Kubernetes cluster during a run: a pool of nodes the services on it run their pods on
 * ({@link NodePool}), grown and shrunk by its {@link ClusterAutoscaler}. It is on no
 * request's way: it has no ports. Its report adds its nodes', pods' and requests' figures
 * ({@link ClusterReport}).
 */
public final class ClusterRuntime extends RuntimeComponent {

	private static final double MS_PER_SECOND = 1000.0;

	private final ClusterConfig config;

	private final NodePool pool;

	/**
	 * A cluster at the start of a run, its nodes empty.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 */
	public ClusterRuntime(SimContext context, NodeSpec node, ClusterConfig config) {
		super(context, node, OptionalInt.empty());
		this.config = config;
		this.pool = new NodePool(context.simulation(), metrics(), config);
		if (config.clusterAutoscaler()) {
			new ClusterAutoscaler(context.simulation(), this.pool, config, metrics()).start();
		}
	}

	/**
	 * Refuses: no request reaches a cluster, which has no ports.
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		throw new UnsupportedOperationException("a cluster has no ports, so no request reaches it");
	}

	/**
	 * The cluster's settings.
	 * @return its config
	 */
	public ClusterConfig config() {
		return this.config;
	}

	/**
	 * Asks for a pod: bound now to the least allocated node with room for its request,
	 * else Pending until a node has room.
	 * @param request what the pod requests
	 * @return the node the pod is bound to, once it is: at once when it is bound now
	 */
	public Promise<ClusterNode> requestPod(PodRequest request) {
		return this.pool.request(request);
	}

	/**
	 * Notes that a node has a service's image: its pod ran there before the run started.
	 * @param node the node
	 * @param serviceId the service
	 */
	public void cacheImage(ClusterNode node, String serviceId) {
		node.cacheImage(serviceId);
	}

	/**
	 * Pulls a service's image onto a node for a pod bound there, so later pods there find
	 * it cached.
	 * @param node the node the pod is bound to
	 * @param serviceId the service
	 * @return the time the pull takes, in milliseconds; 0 when the node has the image
	 */
	public double pullImage(ClusterNode node, String serviceId) {
		return node.cacheImage(serviceId) ? this.config.imagePullS() * MS_PER_SECOND : 0.0;
	}

	@Override
	public SequencedMap<String, @Nullable Number> extraSummary() {
		return ClusterReport.extraSummary(this.pool);
	}

	@Override
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		return ClusterReport.extraTimeseries(this.pool.gauges());
	}

	@Override
	public List<TechnologyTrait> behaviour() {
		return ClusterReport.behaviour(this.config);
	}

	@Override
	public List<Formula> explain(NodeSummary summary) {
		return ClusterReport.explain(this.config, summary);
	}

	@Override
	public void finish(double end) {
		super.finish(end);
		this.pool.finish(end);
	}

}
