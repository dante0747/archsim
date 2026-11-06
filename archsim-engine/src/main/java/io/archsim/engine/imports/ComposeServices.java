package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.roundtrip.RoundTrip;

/**
 * The services of a docker-compose file, read into parts one by one, with the links each
 * states or implies.
 */
final class ComposeServices {

	private final DesignBuilder design;

	private final ImportLog log;

	private final PartNames names;

	private final List<String> published = new ArrayList<>();

	private final Set<String> tools = new HashSet<>();

	/**
	 * Reads services into a design.
	 * @param design the design
	 * @param log the report
	 * @param names the parts by service name, and the links parts state, which reading
	 * fills
	 */
	ComposeServices(DesignBuilder design, ImportLog log, PartNames names) {
		this.design = design;
		this.log = log;
		this.names = names;
	}

	/**
	 * The parts that publish ports.
	 * @return them, preferred as the entry point
	 */
	List<String> published() {
		return Collections.unmodifiableList(this.published);
	}

	/**
	 * The parts whose image is a tool.
	 * @return them, never taken as the entry point
	 */
	Set<String> tools() {
		return Collections.unmodifiableSet(this.tools);
	}

	/**
	 * Reads one service into a part.
	 * @param serviceName the service's name
	 * @param value its settings, as written
	 * @throws ImportRefusedException when they are not a mapping
	 */
	void read(String serviceName, JsonNode value) {
		if (PythonValues.truthy(value) && !value.isObject()) {
			throw new ImportRefusedException("service '" + serviceName + "' is not a mapping");
		}
		ComposeServiceReader service = new ComposeServiceReader(this.design, serviceName,
				PythonMappings.dict(value, "get"));
		service.addTo(this.log);
		this.names.name(serviceName, service.nodeId());
		service.statedCalls().ifPresent((calls) -> this.names.stateCalls(service.nodeId(), calls));
		if (service.isTool()) {
			this.tools.add(service.nodeId());
		}
		if (service.publishesPorts()) {
			this.published.add(service.nodeId());
		}
	}

	/**
	 * Links: exactly as {@code archsim.calls} states them, else from {@code depends_on}
	 * and URL environment values (guesses).
	 * @param services the file's services
	 */
	void link(JsonNode services) {
		services.properties().forEach((service) -> link(service.getKey(), service.getValue()));
	}

	private void link(String serviceName, JsonNode value) {
		String source = this.names.partId(serviceName).orElseThrow();
		String stated = this.names.statedCalls(source).orElse(null);
		if (stated != null) {
			RoundTrip.decodeCalls(stated).forEach((call) -> this.design.link(source, call));
			return;
		}
		ObjectNode settings = PythonMappings.dict(value, "get");
		List<Reason> reasons = new ArrayList<>();
		ComposeSettings.dependencies(settings)
			.forEach((dependency) -> reasons.add(new Reason(dependency, "depends_on")));
		ComposeSettings.environment(settings)
			.forEach((variable, url) -> UrlHosts.of(JsonNodeFactory.instance.stringNode(url))
				.filter(this.names::names)
				.ifPresent((host) -> reasons.add(new Reason(host, variable))));
		for (Reason reason : reasons) {
			Optional<String> target = this.names.partId(reason.service());
			if (target.isPresent() && this.design.link(source, target.get())) {
				this.log.guessed("link " + source + " -> " + target.get() + " (from " + reason.why()
						+ "); request types read and write assumed");
			}
		}
	}

	/**
	 * Why a service is linked to another.
	 *
	 * @param service the other service's name
	 * @param why what names it: {@code depends_on}, or an environment variable
	 */
	private record Reason(String service, String why) {

	}

}
