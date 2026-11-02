package io.archsim.model.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;

/**
 * How the design is drawn: rendering only, it never changes a result.
 *
 * @param nodes each node's position, by node id
 * @param edges where links attach, by edge id
 * @param viewport the part of the canvas shown
 */
public record Layout(@Default("{}") Map<String, Position> nodes, @Default("{}") Map<String, EdgeLayout> edges,
		@Nullable Viewport viewport) {

	/**
	 * Copies the maps, so the layout does not change.
	 */
	public Layout {
		nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		edges = Collections.unmodifiableMap(new LinkedHashMap<>(edges));
	}
}
