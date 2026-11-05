package io.archsim.engine.findings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.model.codec.BindingException;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;

/**
 * A run being analyzed: its report, the design it simulated, each part's status, and each
 * part's settings as its type's record.
 *
 * @param report the run's report
 * @param resolved the design as the run simulated it: resolved and auto-sized
 * @param statuses each part's status, by its id
 * @param configs each part's settings, by its id; none for a part whose settings are not
 * valid for its type
 */
record RunContext(RunReport report, ArchitectureDocument resolved, Map<String, Classification> statuses,
		Map<String, Record> configs) {

	/**
	 * Copies the statuses and settings.
	 */
	RunContext {
		statuses = Map.copyOf(statuses);
		configs = Map.copyOf(configs);
	}

	/**
	 * The context of a run whose parts are classified.
	 * @param report the run's report
	 * @param resolved the design it simulated
	 * @param statuses each part's status, by its id
	 * @param registry the component types, to read each part's settings
	 * @return the context
	 */
	static RunContext of(RunReport report, ArchitectureDocument resolved, Map<String, Classification> statuses,
			ComponentRegistry registry) {
		Map<String, Record> configs = new LinkedHashMap<>();
		for (NodeSpec node : resolved.architecture().nodes()) {
			registry.find(node.type()).ifPresent((spec) -> {
				try {
					configs.put(node.id(), spec.parseConfig(node.config()));
				}
				catch (BindingException ex) {
					// settings that are not valid are left out, as an empty config
				}
			});
		}
		return new RunContext(report, resolved, statuses, configs);
	}

	/**
	 * A part's status.
	 * @param node the part
	 * @return its status
	 */
	Classification status(ReportNode node) {
		return Objects.requireNonNull(this.statuses.get(node.id()), node.id());
	}

	/**
	 * A part's settings, if they are of a type.
	 * @param <T> the settings' type
	 * @param id the part's id
	 * @param type the settings' type, such as {@code DatabaseConfig} or
	 * {@code ClientConfig}
	 * @return its settings; empty when it has none of that type
	 */
	<T> Optional<T> config(String id, Class<T> type) {
		Record config = this.configs.get(id);
		return type.isInstance(config) ? Optional.of(type.cast(config)) : Optional.empty();
	}

}
