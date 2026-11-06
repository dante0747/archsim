package io.archsim.engine;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.ComposeImport;
import io.archsim.engine.imports.ImportRefusedException;
import io.archsim.engine.imports.ImportedDesign;
import io.archsim.engine.imports.KubernetesImport;
import io.archsim.engine.imports.TerraformImport;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.PythonError;

/**
 * The importers, each as the Java call that replaces it, taking a case's input and giving
 * its output as the frozen engine wrote it: the design as built, before validation, with
 * the import report. The golden files record {@code import_design}, which validates what
 * they build, so these are checked on {@code imports/cases.json}.
 */
final class ImportFunctions {

	private static final ComposeImport COMPOSE = new ComposeImport(StandardComponents.registry());

	private static final KubernetesImport KUBERNETES = new KubernetesImport(StandardComponents.registry());

	private static final TerraformImport TERRAFORM = new TerraformImport(StandardComponents.registry());

	private static final Map<String, Function<GoldenEntry, ImportedDesign>> PORTED = Map.of(
			"archsim_engine.importers.compose.import_compose",
			(entry) -> COMPOSE.read(entry.input("text").stringValue(), name(entry)),
			"archsim_engine.importers.k8s.import_k8s",
			(entry) -> KUBERNETES.read(entry.input("text").stringValue(), name(entry)),
			"archsim_engine.importers.terraform.import_terraform",
			(entry) -> TERRAFORM.read(entry.input("text").stringValue(), name(entry)));

	private ImportFunctions() {
	}

	/** The Python functions ported here, by qualified name. */
	static Set<String> names() {
		return PORTED.keySet();
	}

	/**
	 * The Java counterpart of the entry's function on the entry's input. A refusal
	 * becomes the {@code ValueError} the Python engine raises in its place.
	 */
	static JsonNode call(GoldenEntry entry) {
		Function<GoldenEntry, ImportedDesign> counterpart = Objects.requireNonNull(PORTED.get(entry.fn()), entry.fn());
		try {
			ImportedDesign imported = counterpart.apply(entry);
			ObjectNode output = GoldenJson.NODES.objectNode();
			output.set("document", imported.document());
			output.set("report", imported.report().toJson());
			return output;
		}
		catch (ImportRefusedException ex) {
			ObjectNode error = GoldenJson.NODES.objectNode().put("message", ex.getMessage()).put("type", "ValueError");
			throw new PythonError(error, ex);
		}
	}

	private static @Nullable String name(GoldenEntry entry) {
		return GoldenJson.readNullableText(entry.input("name"));
	}

}
