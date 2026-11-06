package io.archsim.engine.export;

import java.util.List;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.ComponentFamily;

/**
 * One compose service as an export writes it: an application's image, replicas and
 * environment, or its technology's image, command, port and storage; what it depends on;
 * and labels for what compose cannot say.
 *
 * @param part the part
 * @param calls its outgoing links, as an export writes them
 * @param targets the parts it calls that compose runs
 */
record ComposeService(DeployPart part, String calls, List<DeployPart> targets) {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The utilization an autoscaled service aims for when it names none. */
	private static final double DEFAULT_TARGET_UTILIZATION = 0.6;

	/** The connections PostgreSQL allows unless the part says otherwise. */
	private static final double POSTGRESQL_CONNECTIONS = 100;

	/** The connections MySQL and MariaDB allow unless the part says otherwise. */
	private static final double MYSQL_CONNECTIONS = 151;

	/**
	 * Copies the targets, so the record does not change.
	 */
	ComposeService {
		targets = List.copyOf(targets);
	}

	/**
	 * The service; nothing but what it depends on and its labels for a part whose
	 * technology has no image.
	 * @param hostPorts the host ports proxies are published on, from which a proxy takes
	 * one
	 * @return the service
	 */
	ObjectNode toJson(HostPorts hostPorts) {
		Draft draft = new Draft(NODES.objectNode(), NODES.objectNode().put("archsim.node", this.part.id()));
		if (!this.calls.isEmpty()) {
			draft.labels().put("archsim.calls", this.calls);
		}
		if (this.part.experimental()) {
			draft.labels().put("archsim.tier", "experimental");
		}
		if (this.part.family() == ComponentFamily.COMPUTE) {
			application(draft);
		}
		else {
			this.part.deployment().ifPresent((spec) -> technology(draft, spec, hostPorts));
		}
		if (!this.targets.isEmpty()) {
			ArrayNode dependsOn = draft.service().putArray("depends_on");
			this.targets.forEach((target) -> dependsOn.add(target.name()));
		}
		draft.service().set("labels", draft.labels());
		return draft.service();
	}

	private void application(Draft draft) {
		draft.service().put("image", "your-org/" + this.part.name() + ":latest");
		ObjectNode deploy = draft.service().putObject("deploy").put("replicas", this.part.replicas());
		PartSpecs.of(this.part.settings())
			.composeResources()
			.ifPresent((resources) -> deploy.set("resources", resources));
		if (this.part.settings().isTrue("autoscaling")) {
			draft.labels()
				.put("archsim.autoscaling", YamlText.number(this.part.number("minReplicas", 1)) + "-"
						+ YamlText.number(this.part.number("maxReplicas", 1)) + " replicas at "
						+ ExportText.whole(
								ExportText.percent(this.part.number("targetUtilization", DEFAULT_TARGET_UTILIZATION)))
						+ "% utilization");
		}
		if (this.part.settings().truthy("platform", false)) {
			draft.labels().put("archsim.platform", this.part.text("platform"));
		}
		this.part.runtime().ifPresent((runtime) -> draft.labels().put("archsim.runtime", runtime));
		ObjectNode environment = NODES.objectNode();
		this.targets
			.forEach((target) -> environment.put(ExportText.environmentName(target.name()) + "_URL", target.url()));
		if (!environment.isEmpty()) {
			draft.service().set("environment", environment);
		}
	}

	private void technology(Draft draft, TechnologySpec spec, HostPorts hostPorts) {
		draft.labels().put("archsim.technology", this.part.technologySetting());
		draft.service().put("image", spec.image());
		if (!spec.environment().isEmpty()) {
			ObjectNode environment = draft.service().putObject("environment");
			spec.environment().forEach(environment::put);
		}
		if (!spec.command().isEmpty()) {
			command(draft, spec.command());
		}
		connectionLimit(draft);
		if (this.part.family() == ComponentFamily.NETWORK) {
			proxy(draft, spec, hostPorts.take());
		}
		PartSpecs.of(this.part.settings())
			.composeResources()
			.ifPresent((resources) -> draft.service().putObject("deploy").set("resources", resources));
		if (this.part.family() == ComponentFamily.DATABASE || this.part.family() == ComponentFamily.MESSAGING) {
			store(draft, spec);
		}
		spec.noteText().ifPresent((note) -> draft.labels().put("archsim.note", note));
	}

	/**
	 * The command that sets a self-hosted database's connection limit.
	 * @param draft the service being written
	 */
	private void connectionLimit(Draft draft) {
		if (this.part.family() != ComponentFamily.DATABASE) {
			return;
		}
		if ("postgresql".equals(this.part.technology())) {
			command(draft, List.of("postgres", "-c",
					"max_connections=" + YamlText.number(this.part.number("maxConnections", POSTGRESQL_CONNECTIONS))));
		}
		else if ("mysql".equals(this.part.technology()) || "mariadb".equals(this.part.technology())) {
			command(draft, List
				.of("--max-connections=" + YamlText.number(this.part.number("maxConnections", MYSQL_CONNECTIONS))));
		}
	}

	private void proxy(Draft draft, TechnologySpec spec, int hostPort) {
		draft.service().putArray("ports").add(hostPort + ":" + spec.port());
		double rateLimit = this.part.number("rateLimitRps", 0);
		if (rateLimit > 0) {
			draft.labels().put("archsim.rate-limit", YamlText.number(rateLimit) + " req/s (configure in the proxy)");
		}
	}

	private void store(Draft draft, TechnologySpec spec) {
		draft.service().putArray("volumes").add(this.part.name() + "-data:" + spec.dataDirectory());
		Double storage = PartSpecs.of(this.part.settings()).storageGib();
		if (storage != null) {
			draft.labels()
				.put("archsim.storage", YamlText.number(storage) + " GiB per instance (size the volume to match)");
		}
		double instances = this.part.instances();
		if (instances > 1) {
			draft.labels()
				.put("archsim.topology",
						YamlText.number(instances) + " instances modelled (shards "
								+ YamlText.number(this.part.number("shards", 1)) + ", read replicas "
								+ YamlText.number(this.part.number("readReplicas", 0)) + ", RF "
								+ YamlText.number(this.part.number("replicationFactor", 1))
								+ "); configure replication/sharding separately");
		}
		if (this.part.family() == ComponentFamily.MESSAGING) {
			draft.labels().put("archsim.messaging", ExportText.messaging(this.part));
		}
	}

	private static void command(Draft draft, List<String> arguments) {
		ArrayNode command = draft.service().putArray("command");
		arguments.forEach(command::add);
	}

	/**
	 * A service being written, and its labels, which go last.
	 *
	 * @param service the service's settings so far
	 * @param labels its labels so far
	 */
	private record Draft(ObjectNode service, ObjectNode labels) {

	}

}
