package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.imports.DesignImporter;
import io.archsim.engine.imports.ImportFormat;
import io.archsim.engine.imports.ImportRefusedException;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.GoldenEntry;

/**
 * Importing a design from docker-compose, Kubernetes manifests or a Terraform plan,
 * validated: {@code import_design}, which the golden files record.
 */
final class ImportDesignFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.of("archsim_engine.importers.import_design",
			ImportDesignFunctions::importDesign);

	private static final DesignImporter IMPORTER = new DesignImporter(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private ImportDesignFunctions() {
	}

	/**
	 * {@code {document, report}}; a refusal becomes the {@code ValueError} the Python
	 * engine raises in its place.
	 */
	private static JsonNode importDesign(GoldenEntry entry) {
		try {
			return IMPORTER
				.importDesign(ImportFormat.named(entry.input("fmt").stringValue()), entry.input("text").stringValue(),
						GoldenJson.readNullableText(entry.input("name")))
				.toJson();
		}
		catch (ImportRefusedException ex) {
			throw ImportFunctions.valueError(ex);
		}
	}

}
