package io.archsim.model.validation;

import java.util.List;
import java.util.Optional;

import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;

/**
 * Runs each component type's own rules on the nodes of that type whose config is valid.
 */
final class ComponentRuleChecks {

	private final ValidationContext context;

	ComponentRuleChecks(ValidationContext context) {
		this.context = context;
	}

	/**
	 * Runs the rules of every node's type.
	 * @param view the design as the rules see it, with the request types that reach each
	 * node when they were worked out
	 */
	void check(GraphView view) {
		for (NodeSpec node : this.context.declaredNodes()) {
			Record config = this.context.config(node.id());
			Optional<ComponentSpec<?>> spec = this.context.spec(node);
			if (config != null && spec.isPresent()) {
				rules(spec.get(), node, config, view).forEach(this.context::report);
			}
		}
	}

	/**
	 * A component type's rules on a node, when the node's config is of the type's config.
	 * @param <C> the type's config
	 * @param spec the node's component type
	 * @param node the node to check
	 * @param config the node's parsed config
	 * @param view the design as the rules see it
	 * @return the issues the rules find; none when the config is of another type
	 */
	private static <C extends Record> List<Issue> rules(ComponentSpec<C> spec, NodeSpec node, Record config,
			GraphView view) {
		return spec.configType().isInstance(config) ? spec.validate(node, spec.configType().cast(config), view)
				: List.of();
	}

}
