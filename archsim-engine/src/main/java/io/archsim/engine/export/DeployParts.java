package io.archsim.engine.export;

import java.util.List;
import java.util.Optional;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.Tier;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;

/**
 * The parts of a design the exports deploy: every part but a Kubernetes cluster, which is
 * where parts run, not one of them.
 */
final class DeployParts {

	private final ComponentRegistry registry;

	/**
	 * Reads designs made of a registry's types.
	 * @param registry the component types
	 */
	DeployParts(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * The parts the exports deploy.
	 * @param document the design
	 * @return its parts but its Kubernetes clusters, in order
	 */
	List<DeployPart> of(ArchitectureDocument document) {
		return document.architecture()
			.nodes()
			.stream()
			.filter((node) -> !node.type().equals(KubernetesClusterSpec.TYPE))
			.map(this::part)
			.toList();
	}

	private DeployPart part(NodeSpec node) {
		Optional<ComponentSpec<?>> spec = this.registry.find(node.type());
		LenientSettings settings = spec.map((type) -> LenientSettings.overDefaults(type, node.config()))
			.orElseGet(() -> LenientSettings.of(node.config()));
		ComponentFamily family = ComponentFamily.of(node.type(), this.registry);
		Optional<String> own = settings.text("technology");
		Optional<String> technology = own.filter((name) -> !name.equals(DeployPart.GENERIC))
			.or(() -> TechnologySpecs.defaultFor(family));
		boolean experimental = spec.flatMap((type) -> own.map(type.technologies()::get))
			.filter((profile) -> profile.tier() == Tier.EXPERIMENTAL)
			.isPresent();
		return new DeployPart(node, family, settings, ExportText.dnsName(node.id()), technology.orElse(null),
				technology.flatMap(TechnologySpecs::of).orElse(null), experimental);
	}

}
