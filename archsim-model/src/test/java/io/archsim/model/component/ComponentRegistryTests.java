package io.archsim.model.component;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The component types a design may use, and what their configs work out from their
 * settings.
 */
class ComponentRegistryTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void types_standardRegistry_listsThemInThePythonEnginesOrder() {
		assertThat(StandardComponents.registry().types()).containsExactly("traffic_source", "mobile_app", "web_client",
				"api_consumer", "load_balancer", "service", "cache", "database", "external_api", "message_queue",
				"kubernetes_cluster");
	}

	@Test
	void register_sameTypeTwice_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ComponentRegistry(List.of(new ExternalApiSpec(), new ExternalApiSpec())))
			.withMessage("component type 'external_api' is already registered");
	}

	@Test
	void parseConfig_emptyObject_takesEveryDefault() {
		for (ComponentSpec<?> spec : StandardComponents.registry().specs()) {
			Record config = spec.parseConfig(JSON.createObjectNode());
			assertThat(RecordCodec.read(RecordCodec.write(config), spec.configType())).as(spec.type())
				.isEqualTo(config);
		}
	}

	@Test
	void executors_autoSetting_followsTheTechnologyUnlessExplicit() {
		CacheConfig redis = cache(Map.of("technology", "redis", "concurrency", 4));
		CacheConfig memcached = cache(Map.of("technology", "memcached", "concurrency", 4));
		CacheConfig forced = cache(Map.of("technology", "redis", "concurrency", 4, "singleThreaded", "off"));
		assertThat(redis.executors()).isEqualTo(1);
		assertThat(memcached.executors()).isEqualTo(4);
		assertThat(forced.executors()).isEqualTo(4);
	}

	@Test
	void technologyProfiles_technologyWithoutProfile_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new TechnologyProfiles<>(Two.class, Map.of(Two.ONE, TechnologyProfile.core())))
			.withMessage("technologies without a profile: [TWO]");
	}

	private static CacheConfig cache(Map<String, Object> settings) {
		ObjectNode json = JSON.valueToTree(settings);
		return RecordCodec.read(json, CacheConfig.class);
	}

	/** Two technologies, of which a test gives only the first a profile. */
	private enum Two {

		/** The technology with a profile. */
		ONE,
		/** The technology without one. */
		TWO

	}

}
