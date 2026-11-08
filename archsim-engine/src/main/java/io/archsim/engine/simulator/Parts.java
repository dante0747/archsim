package io.archsim.engine.simulator;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.components.external.ExternalApiRuntime;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.client.ClientConfig;
import io.archsim.model.component.external.ExternalApiConfig;
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
	 * @throws UnsupportedOperationException for a type the Java simulator does not
	 * simulate yet
	 */
	static RuntimeComponent build(SimContext context, NodeSpec node, Record config, ClientMetrics clients) {
		return switch (config) {
			case ClientConfig client -> new TrafficSourceRuntime(context, node, client, clients);
			case ExternalApiConfig api -> new ExternalApiRuntime(context, node, api);
			default -> throw new UnsupportedOperationException(
					"the Java simulator does not simulate a " + node.type() + " yet");
		};
	}

}
