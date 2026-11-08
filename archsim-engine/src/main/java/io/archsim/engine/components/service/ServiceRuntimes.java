package io.archsim.engine.components.service;

import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.service.Platform;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.document.NodeSpec;

/**
 * The runtime a service takes during a run, by its platform: a serverless function, or a
 * service with fixed or autoscaling replicas, on a Kubernetes cluster or not.
 */
public final class ServiceRuntimes {

	private ServiceRuntimes() {
	}

	/**
	 * A service at the start of a run.
	 * @param context the run, with the design's clusters named
	 * @param node the part
	 * @param config its settings
	 * @return its runtime
	 * @throws UnsupportedOperationException for an autoscaling service on a Kubernetes
	 * cluster, which comes with its slice of the port
	 */
	public static RuntimeComponent of(SimContext context, NodeSpec node, ServiceConfig config) {
		if (config.platform() == Platform.SERVERLESS) {
			return new ServerlessRuntime(context, node, config);
		}
		if (config.autoscaling() && config.clusterIn(context.clusterIds()) != null) {
			throw new UnsupportedOperationException(
					"the Java simulator does not simulate an autoscaling service on a Kubernetes cluster yet");
		}
		return new ServiceRuntime(context, node, config);
	}

}
