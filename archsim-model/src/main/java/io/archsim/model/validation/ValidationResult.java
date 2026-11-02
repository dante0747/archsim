package io.archsim.model.validation;

import io.archsim.model.component.GraphView;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What validating a design found, and what it worked out on the way: each valid node's
 * config, the design as component rules saw it, and the request types each link carries.
 *
 * @param issues every issue, in the order the checks ran
 * @param configs each node's parsed config, for the nodes whose config is valid
 * @param view the design's valid links and routing
 * @param edgeFlows the request types each link carries, for the links routing reached
 * (empty when routing was not worked out)
 */
public record ValidationResult(List<Issue> issues, Map<String, Record> configs, GraphView view,
		Map<String, Set<RequestType>> edgeFlows) {

	public ValidationResult {
		issues = List.copyOf(issues);
		configs = Collections.unmodifiableMap(new LinkedHashMap<>(configs));
		edgeFlows = Collections.unmodifiableMap(new LinkedHashMap<>(edgeFlows));
	}

	public List<Issue> errors() {
		return issues.stream().filter(Issue::isError).toList();
	}

	public List<Issue> warnings() {
		return issues.stream().filter(i -> !i.isError()).toList();
	}

	/** Whether nothing stops a simulation: no errors (warnings allowed). */
	public boolean valid() {
		return errors().isEmpty();
	}

	/** {@code {valid, issues}}, as the API returns it. */
	public ObjectNode toJson() {
		ObjectNode json = JsonNodeFactory.instance.objectNode().put("valid", valid());
		issues.stream().map(Issue::toJson).forEach(json.putArray("issues")::add);
		return json;
	}
}
