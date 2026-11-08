package io.archsim.engine.components.service;

import java.util.List;
import java.util.Map;

import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.ServiceSpec;

/**
 * The behaviours each service technology brings, read once from the component catalog.
 */
final class ServiceTraits {

	private static final Map<String, List<TechnologyTrait>> BY_TECHNOLOGY = ComponentCatalog
		.technologyTraits(ServiceSpec.TYPE);

	private ServiceTraits() {
	}

	/**
	 * The behaviours a service's technology brings, each shown when it is in effect.
	 * @param config its settings
	 * @return the traits, in the technology's order
	 */
	static List<TechnologyTrait> of(ServiceConfig config) {
		return BY_TECHNOLOGY.getOrDefault(config.technology().json(), List.of());
	}

}
