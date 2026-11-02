package io.archsim.model.validation;

import java.util.Locale;
import java.util.Optional;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;

/**
 * Checks that each node names a known component type and gives it a config the type
 * accepts, and records each valid config for the checks that follow.
 */
final class ComponentTypeChecks {

	private final ValidationContext context;

	ComponentTypeChecks(ValidationContext context) {
		this.context = context;
	}

	void check() {
		for (NodeSpec node : this.context.declaredNodes()) {
			Optional<ComponentSpec<?>> spec = this.context.spec(node);
			if (spec.isEmpty()) {
				this.context.report(unknownType(node));
				continue;
			}
			try {
				this.context.putConfig(node.id(), spec.get().parseConfig(node.config()));
			}
			catch (BindingException ex) {
				ex.errors().forEach((error) -> this.context.report(configIssue(node, error)));
			}
		}
	}

	private Issue unknownType(NodeSpec node) {
		return Issue
			.error("node.unknown_type",
					"Node '%s' has unknown component type '%s'.".formatted(node.name(), node.type()))
			.atNode(node.id())
			.withHint("Known types: %s.".formatted(String.join(", ", this.context.knownTypes())));
	}

	private static Issue configIssue(NodeSpec node, BindingError error) {
		String where = error.location().toString();
		return Issue
			.error("node.config_invalid",
					"%s: '%s' %s".formatted(node.name(), where, error.message().toLowerCase(Locale.ROOT)))
			.atNode(node.id())
			.atPath(where)
			.withHint("Fix the highlighted field in the inspector.");
	}

}
