package io.archsim.engine.simulator;

import io.archsim.engine.components.cache.CacheRuntime;
import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.components.database.DatabaseRuntime;
import io.archsim.engine.components.external.ExternalApiRuntime;
import io.archsim.engine.components.kubernetes.ClusterRuntime;
import io.archsim.engine.components.loadbalancer.LoadBalancerRuntime;
import io.archsim.engine.components.queue.MessageQueueRuntime;
import io.archsim.engine.components.service.ServiceRuntimes;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.client.ClientConfig;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.external.ExternalApiConfig;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.loadbalancer.LoadBalancerConfig;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.document.NodeSpec;

/**
 * Each part's behaviour during a run, by the type of its config.
 */
final class Parts {

	private Parts() {
	}

	/**
	 * A part at the start of a run.
	 * @param context the run
	 * @param node the part
	 * @param config its validated config
	 * @param clients the run's end-to-end client metrics, which client groups share
	 * @return the part
	 * @throws UnsupportedOperationException for an autoscaling service on a Kubernetes
	 * cluster, which the Java simulator does not simulate yet
	 * @throws IllegalArgumentException for a config no standard part has
	 */
	static RuntimeComponent build(SimContext context, NodeSpec node, Record config, ClientMetrics clients) {
		return switch (config) {
			case ClientConfig client -> new TrafficSourceRuntime(context, node, client, clients);
			case ExternalApiConfig api -> new ExternalApiRuntime(context, node, api);
			case ServiceConfig service -> ServiceRuntimes.of(context, node, service);
			case LoadBalancerConfig balancer -> new LoadBalancerRuntime(context, node, balancer);
			case CacheConfig cache -> new CacheRuntime(context, node, cache);
			case DatabaseConfig database -> new DatabaseRuntime(context, node, database);
			case MessageQueueConfig queue -> new MessageQueueRuntime(context, node, queue);
			case ClusterConfig cluster -> new ClusterRuntime(context, node, cluster);
			default -> throw new IllegalArgumentException("no part takes a " + config.getClass().getSimpleName());
		};
	}

}
