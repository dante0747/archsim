package io.archsim.model.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.component.standard.StandardComponents;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The component types a design may use, and what their configs work out from their
 * settings.
 */
class ComponentRegistryTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void the_standard_registry_lists_the_types_in_the_python_engines_order() {
		assertThat(StandardComponents.registry().types()).containsExactly("traffic_source", "mobile_app", "web_client",
				"api_consumer", "load_balancer", "service", "cache", "database", "external_api", "message_queue",
				"kubernetes_cluster");
	}

	@Test
	void a_type_is_registered_once() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ComponentRegistry(List.of(new ExternalApiSpec(), new ExternalApiSpec())))
			.withMessage("component type 'external_api' is already registered");
	}

	@Test
	void every_config_reads_from_an_empty_object_with_its_defaults() {
		for (ComponentSpec<?> spec : StandardComponents.registry().specs()) {
			Record config = spec.parseConfig(JSON.createObjectNode());
			assertThat(RecordCodec.read(RecordCodec.write(config), spec.configType())).as(spec.type())
				.isEqualTo(config);
		}
	}

	@Test
	void an_auto_setting_follows_the_technology_and_an_explicit_one_wins() {
		CacheConfig redis = cache(Map.of("technology", "redis", "concurrency", 4));
		CacheConfig memcached = cache(Map.of("technology", "memcached", "concurrency", 4));
		CacheConfig forced = cache(Map.of("technology", "redis", "concurrency", 4, "singleThreaded", "off"));
		assertThat(redis.executors()).isEqualTo(1);
		assertThat(memcached.executors()).isEqualTo(4);
		assertThat(forced.executors()).isEqualTo(4);
	}

	@Test
	void a_technology_without_a_profile_is_refused_when_the_table_is_built() {
		enum Two {

			ONE, TWO

		}
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new TechnologyProfiles<>(Two.class, Map.of(Two.ONE, TechnologyProfile.core())))
			.withMessage("technologies without a profile: [TWO]");
	}

	private static CacheConfig cache(Map<String, Object> settings) {
		ObjectNode json = JSON.valueToTree(settings);
		return RecordCodec.read(json, CacheConfig.class);
	}

}
