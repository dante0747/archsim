package io.archsim.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.export.DesignExporter;
import io.archsim.engine.imports.DesignImporter;
import io.archsim.engine.run.RunPreparer;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.validation.DocumentValidator;

/**
 * The design model and the engine the API works with: ArchSim's component types, the
 * price snapshots it ships plus those of {@code ARCHSIM_PRICES_DIR}, and what checks and
 * resolves, analyzes, prepares and exports designs with them.
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

	@Bean
	DocumentValidator documentValidator(ComponentRegistry registry, PriceSnapshots prices) {
		return new DocumentValidator(registry, prices);
	}

	@Bean
	ScenarioResolver scenarioResolver(ComponentRegistry registry, PriceSnapshots prices) {
		return new ScenarioResolver(registry, prices);
	}

	@Bean
	DesignAnalyzer designAnalyzer(ComponentRegistry registry, PriceSnapshots prices) {
		return new DesignAnalyzer(registry, prices);
	}

	@Bean
	DesignExporter designExporter(ComponentRegistry registry, PriceSnapshots prices) {
		return new DesignExporter(registry, prices);
	}

	@Bean
	DesignImporter designImporter(ComponentRegistry registry, PriceSnapshots prices) {
		return new DesignImporter(registry, prices);
	}

	@Bean
	RunPreparer runPreparer(ComponentRegistry registry, PriceSnapshots prices) {
		return new RunPreparer(registry, prices);
	}

}
