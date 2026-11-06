package io.archsim.engine.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.model.document.EdgeSpec;

/**
 * The links of a design between the parts an export deploys.
 *
 * @param links the design's links, in order
 * @param parts the parts deployed, by id; the last part with an id when two share it
 */
record ExportLinks(List<EdgeSpec> links, Map<String, DeployPart> parts) {

	/**
	 * Copies the links and the parts, so the record does not change.
	 */
	ExportLinks {
		links = List.copyOf(links);
		parts = Collections.unmodifiableMap(new LinkedHashMap<>(parts));
	}

	/**
	 * The links between the parts deployed.
	 * @param links the design's links
	 * @param deployed the parts deployed
	 * @return the links
	 */
	static ExportLinks of(List<EdgeSpec> links, List<DeployPart> deployed) {
		Map<String, DeployPart> parts = new LinkedHashMap<>();
		deployed.forEach((part) -> parts.put(part.id(), part));
		return new ExportLinks(links, parts);
	}

	/**
	 * A part's outgoing links.
	 * @param part the part
	 * @return its links, in order
	 */
	List<EdgeSpec> from(DeployPart part) {
		return this.links.stream().filter((link) -> link.source().equals(part.id())).toList();
	}

	/**
	 * The parts a part calls, each once, other than clients.
	 * @param part the calling part
	 * @return the deployed parts its links lead to, in the links' order, without
	 * duplicates
	 */
	List<DeployPart> targets(DeployPart part) {
		List<DeployPart> targets = new ArrayList<>();
		for (EdgeSpec link : from(part)) {
			@Nullable DeployPart target = this.parts.get(link.target());
			if (target != null && target.family() != ComponentFamily.TRAFFIC && !targets.contains(target)) {
				targets.add(target);
			}
		}
		return targets;
	}

}
