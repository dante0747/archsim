package io.archsim.model.document;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * A component instance in the architecture graph. Its {@code config} stays plain JSON
 * here, so the document does not depend on the component catalog; validation reads it as
 * its type's config.
 *
 * @param id unique among the nodes
 * @param type a registered component type, such as {@code service}
 * @param label the display name ({@code ""} for none)
 * @param description a longer description
 * @param config the component's settings, as JSON; a copy goes in and comes out
 */
@Description("""
		A component instance in the architecture graph.

		``config`` is validated by the component model registered for ``type``; it is kept as a
		plain mapping here so the document schema does not depend on the component catalog.""")
public record NodeSpec(@Description("Unique node id (letters, digits, _ . -)") @Matches(Patterns.ID) String id,

		@Description("Registered component type") @MinLength(1) @MaxLength(64) String type,

		@Default("") @MaxLength(120) String label, @MaxLength(2000) @Nullable String description,
		@Default("{}") ObjectNode config) {

	/**
	 * Copies the config, so the node does not change.
	 */
	public NodeSpec {
		config = config.deepCopy();
	}

	/**
	 * The settings, as a copy: a record does not change.
	 * @return a copy of the config
	 */
	@Override
	public ObjectNode config() {
		return this.config.deepCopy();
	}

	/**
	 * The label, or the id when there is none.
	 * @return how messages name the node
	 */
	public String name() {
		return this.label.isEmpty() ? this.id : this.label;
	}
}
