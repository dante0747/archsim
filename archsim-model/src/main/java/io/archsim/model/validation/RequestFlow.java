package io.archsim.model.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.archsim.model.component.GraphView;
import io.archsim.model.document.RequestType;

/**
 * Where the request types go: the design as component rules see it, and the request types
 * each link carries.
 *
 * @param view the design's valid links, with the request types that reach each node when
 * they were worked out
 * @param edgeFlows the request types each link carries, for the links routing reached
 * (empty when routing was not worked out)
 */
record RequestFlow(GraphView view, Map<String, Set<RequestType>> edgeFlows) {

	RequestFlow {
		edgeFlows = Collections.unmodifiableMap(new LinkedHashMap<>(edgeFlows));
	}

	/**
	 * The flow of a design whose request types are not followed: one with a loop, or not
	 * exactly one client.
	 * @param view the design as component rules see it, with no incoming types
	 * @return that view, with no link's request types worked out
	 */
	static RequestFlow unrouted(GraphView view) {
		return new RequestFlow(view, Map.of());
	}

}
