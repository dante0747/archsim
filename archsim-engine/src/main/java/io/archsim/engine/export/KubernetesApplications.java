package io.archsim.engine.export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.roundtrip.RoundTrip;
import io.archsim.engine.text.HalfUp;

/**
 * The Kubernetes objects of an application service: a Deployment with its Service and,
 * when it autoscales, a HorizontalPodAutoscaler; or a Knative Service when it is
 * serverless.
 */
final class KubernetesApplications {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The port the application listens on. */
	private static final int APP_PORT = 8080;

	/** The port its Service listens on. */
	private static final int SERVICE_PORT = 80;

	/** How often its readiness is probed, in seconds. */
	private static final int PROBE_PERIOD_S = 5;

	/** How far a serverless service scales unless it says otherwise. */
	private static final double DEFAULT_CONCURRENCY_LIMIT = 1000;

	/** The utilization an autoscaled service aims for unless it says otherwise. */
	private static final double DEFAULT_TARGET_UTILIZATION = 0.6;

	/**
	 * How often an autoscaled service may scale up unless it says otherwise, in seconds.
	 */
	private static final double DEFAULT_SCALE_INTERVAL_S = 15;

	/** How much an autoscaled service may grow at a time, in percent. */
	private static final int SCALE_UP_PERCENT = 100;

	private KubernetesApplications() {
	}

	/**
	 * The objects of an application service.
	 * @param part the part as Kubernetes objects
	 * @param calls its outgoing links, as an export writes them
	 * @param dependencies the parts it calls
	 * @param cpuNeed the CPU it needs when it was auto-sized; {@code null} when its CPU
	 * was set
	 * @return its objects, in order
	 */
	static List<ObjectNode> of(KubernetesPart part, String calls, List<DeployPart> dependencies,
			@Nullable Double cpuNeed) {
		DeployPart service = part.deployed();
		ObjectNode metadata = part.metadata(calls, identity(service));
		ObjectNode container = container(part, dependencies, cpuNeed);
		if (service.settings().is("platform", "serverless")) {
			container.remove("readinessProbe");
			return List.of(knativeService(part, metadata, container));
		}
		ObjectNode spec = NODES.objectNode().put("replicas", service.replicas());
		spec.putObject("selector").set("matchLabels", part.selector());
		spec.set("template", part.podTemplate(container));
		ObjectNode deployment = NODES.objectNode().put("apiVersion", "apps/v1").put("kind", "Deployment");
		deployment.set("metadata", metadata);
		deployment.set("spec", spec);
		ObjectNode clusterService = part.service(SERVICE_PORT, APP_PORT, null);
		if (!service.settings().isTrue("autoscaling")) {
			return List.of(deployment, clusterService);
		}
		return List.of(deployment, clusterService, autoscaler(part));
	}

	/**
	 * What the service runs on and in, as annotations.
	 * @param service the service
	 * @return its platform and its runtime, either null when it has none
	 */
	private static Map<String, JsonNode> identity(DeployPart service) {
		Map<String, JsonNode> identity = new LinkedHashMap<>();
		identity.put("archsim.dev/platform", service.settings().value("platform"));
		identity.put("archsim.dev/runtime",
				service.runtime().<JsonNode>map(NODES::stringNode).orElseGet(NODES::nullNode));
		return identity;
	}

	private static ObjectNode container(KubernetesPart part, List<DeployPart> dependencies, @Nullable Double cpuNeed) {
		DeployPart service = part.deployed();
		ObjectNode container = NODES.objectNode()
			.put("name", service.name())
			.put("image", "your-org/" + service.name() + ":latest");
		container.putArray("ports").addObject().put("containerPort", APP_PORT);
		ArrayNode environment = part.environment(dependencies);
		if (!environment.isEmpty()) {
			container.set("env", environment);
		}
		ObjectNode probe = container.putObject("readinessProbe");
		probe.putObject("httpGet").put("path", "/healthz").put("port", APP_PORT);
		probe.put("periodSeconds", PROBE_PERIOD_S);
		container.set("resources",
				PartSpecs.of(service.settings())
					.kubernetesResources(cpuNeed)
					.orElseGet(RoundTrip::placeholderResources));
		return container;
	}

	private static ObjectNode knativeService(KubernetesPart part, ObjectNode metadata, ObjectNode container) {
		DeployPart service = part.deployed();
		ObjectNode template = NODES.objectNode();
		template.putObject("metadata")
			.putObject("annotations")
			.put("autoscaling.knative.dev/min-scale", "0")
			.put("autoscaling.knative.dev/initial-scale", YamlText.number(service.replicas()))
			.put("autoscaling.knative.dev/max-scale",
					YamlText.number(service.number("concurrencyLimit", DEFAULT_CONCURRENCY_LIMIT)));
		ObjectNode podSpec = template.putObject("spec")
			.put("containerConcurrency", Math.max(1, service.number("workersPerReplica", 1)));
		podSpec.putArray("containers").add(container);
		ObjectNode knative = NODES.objectNode().put("apiVersion", "serving.knative.dev/v1").put("kind", "Service");
		knative.set("metadata", metadata);
		knative.putObject("spec").set("template", template);
		return knative;
	}

	private static ObjectNode autoscaler(KubernetesPart part) {
		DeployPart service = part.deployed();
		ObjectNode spec = NODES.objectNode();
		spec.putObject("scaleTargetRef")
			.put("apiVersion", "apps/v1")
			.put("kind", "Deployment")
			.put("name", service.name());
		double minReplicas = service.number("minReplicas", 1);
		spec.put("minReplicas", Math.max(1, minReplicas));
		spec.put("maxReplicas", Math.max(1, service.number("maxReplicas", minReplicas)));
		ObjectNode resource = spec.putArray("metrics").addObject().put("type", "Resource").putObject("resource");
		resource.put("name", "cpu")
			.putObject("target")
			.put("type", "Utilization")
			.put("averageUtilization",
					ExportText.percent(service.number("targetUtilization", DEFAULT_TARGET_UTILIZATION)));
		spec.putObject("behavior").putObject("scaleUp").putArray("policies").add(scaleUpPolicy(service));
		ObjectNode autoscaler = NODES.objectNode()
			.put("apiVersion", "autoscaling/v2")
			.put("kind", "HorizontalPodAutoscaler");
		autoscaler.set("metadata", part.metadata());
		autoscaler.set("spec", spec);
		return autoscaler;
	}

	/**
	 * How fast an autoscaled service may grow: doubling, once per its scale interval.
	 * @param service the service
	 * @return the policy
	 */
	private static ObjectNode scaleUpPolicy(DeployPart service) {
		return NODES.objectNode()
			.put("type", "Percent")
			.put("value", SCALE_UP_PERCENT)
			.put("periodSeconds",
					Math.max(1, HalfUp.javaScriptRound(service.number("scaleIntervalS", DEFAULT_SCALE_INTERVAL_S))));
	}

}
