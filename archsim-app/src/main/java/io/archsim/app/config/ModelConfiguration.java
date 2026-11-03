package io.archsim.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The design model the API works with: ArchSim's component types, and the price snapshots
 * it ships plus those of {@code ARCHSIM_PRICES_DIR}.
 */
@Configuration(proxyBeanMethods = false)
class ModelConfiguration {

	@Bean
	ComponentRegistry componentRegistry() {
		return StandardComponents.registry();
	}

	@Bean
	PriceSnapshots priceSnapshots(ArchsimProperties properties) {
		return PriceSnapshots.with(properties.priceFolders());
	}

}
