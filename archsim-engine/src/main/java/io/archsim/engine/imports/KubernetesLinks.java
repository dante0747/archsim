package io.archsim.engine.imports;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.Manifests.Workload;
import io.archsim.engine.roundtrip.RoundTrip;

/**
 * The links of a design imported from Kubernetes manifests: as a part's
 * {@code archsim.dev/calls} states them, else from a workload's environment URLs naming
 * another workload's Service (guesses).
 */
final class KubernetesLinks {

	private final DesignBuilder design;

	private final ImportLog log;

	private final PartNames names;

	/**
	 * Links a design's parts.
	 * @param design the design
	 * @param log the report
	 * @param names the parts by name, and the links parts state
	 */
	KubernetesLinks(DesignBuilder design, ImportLog log, PartNames names) {
		this.design = design;
		this.log = log;
		this.names = names;
	}

	/**
	 * Links the workloads, then the other parts whose links are stated (managed services,
	 * third parties).
	 * @param objects the workloads
	 */
	void link(List<Workload> objects) {
		Set<String> workloadIds = new HashSet<>();
		for (Workload workload : objects) {
			String source = this.names.partId(workload.name()).orElseThrow();
			workloadIds.add(source);
			link(source, workload);
		}
		this.names.statedCalls().forEach((source, text) -> {
			if (!workloadIds.contains(source)) {
				RoundTrip.decodeCalls(text).forEach((call) -> this.design.link(source, call));
			}
		});
	}

	private void link(String source, Workload workload) {
		String stated = this.names.statedCalls(source).orElse(null);
		if (stated != null) {
			RoundTrip.decodeCalls(stated).forEach((call) -> this.design.link(source, call));
			return;
		}
		for (JsonNode variable : PythonValues.items(workload.container().path("env"))) {
			linkByUrl(source, PythonMappings.mapping(variable));
		}
	}

	private void linkByUrl(String source, ObjectNode variable) {
		String target = UrlHosts.of(variable.path("value")).flatMap(this.names::partId).orElse(null);
		if (target != null && this.design.link(source, target)) {
			this.log.guessed("link " + source + " -> " + target + " (from " + PythonValues.str(variable.path("name"))
					+ "); request types assumed");
		}
	}

}
