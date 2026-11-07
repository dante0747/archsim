package io.archsim.engine.calibration;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.text.PythonText;

/**
 * A part's config read as the Python engine's calibration reads it: parsed with its
 * type's defaults, and asked for what a type may not have
 * ({@code getattr(cfg, name, default)}).
 */
final class PartConfigs {

	private static final double DEFAULT_READ_RATIO = 0.9;

	private PartConfigs() {
	}

	/**
	 * A part's config with its type's defaults.
	 * @param registry the design's types
	 * @param node the part
	 * @return its config, as the type's record writes it
	 */
	static ObjectNode parsed(ComponentRegistry registry, ObjectNode node) {
		ComponentSpec<?> spec = registry.find(node.path("type").asString())
			.orElseThrow(() -> new IllegalStateException("no type " + node.path("type")));
		return RecordCodec.write(spec.parseConfig(CalibrationRun.config(node)));
	}

	/**
	 * A part's replicas, as {@code float(getattr(cfg, "replicas", 1) or 1)}.
	 * @param config the parsed config
	 * @return its replicas; 1 when it has none
	 */
	static double replicas(ObjectNode config) {
		JsonNode replicas = config.path("replicas");
		return PythonText.truthy(replicas) ? replicas.doubleValue() : 1.0;
	}

	/**
	 * Whether a part's replicas change with load: it autoscales, or runs as a function.
	 * @param config the parsed config
	 * @return whether they do
	 */
	static boolean scales(ObjectNode config) {
		return PythonText.truthy(config.path("autoscaling")) || serverless(config);
	}

	/**
	 * Whether a part runs as a function, one request per instance.
	 * @param config the parsed config
	 * @return whether its platform is {@code serverless}
	 */
	static boolean serverless(ObjectNode config) {
		return "serverless".equals(config.path("platform").asString(null));
	}

	/**
	 * The mean time of a request's work that a part's CPU share applies to: a service's
	 * service time; a database's read and write query times weighted by the reads and
	 * writes it gets (its log flush needs no CPU).
	 * @param registry the design's types
	 * @param document the design
	 * @param node the part
	 * @return the mean, in milliseconds
	 */
	static double meanServiceMs(ComponentRegistry registry, ObjectNode document, ObjectNode node) {
		LenientSettings settings = LenientSettings.of(parsed(registry, node));
		if (!"database".equals(node.path("type").asString())) {
			return settings.meanMs("serviceTimeMs");
		}
		double read = settings.meanMs("readServiceTimeMs");
		double write = settings.meanMs("writeServiceTimeMs");
		ArchitectureDocument design = DesignDocuments.parse(document);
		Map<String, @Nullable NodeLoad> loads = new LoadEstimator(registry)
			.estimate(design.architecture(), design.workload(), design.workload().peakRate(),
					new CacheMisses(Map.of(), null))
			.nodes();
		NodeLoad load = loads.get(node.path("id").asString());
		double share;
		if (load != null && load.read() + load.write() > 0) {
			share = load.read() / (load.read() + load.write());
		}
		else {
			JsonNode ratio = document.path("workload").path("readRatio");
			share = ratio.isMissingNode() ? DEFAULT_READ_RATIO : ratio.doubleValue();
		}
		return share * read + (1 - share) * write;
	}

}
