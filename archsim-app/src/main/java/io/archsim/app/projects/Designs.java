package io.archsim.app.projects;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.web.ApiException;
import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The designs a project saves: upgraded to the current schema and checked against it, and
 * the blank one a new project starts from. A design whose parts are not yet linked can be
 * saved; {@code /validate} reports that, and a run refuses it.
 */
@Component
class Designs {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final PriceSnapshots prices;

	Designs(PriceSnapshots prices) {
		this.prices = prices;
	}

	/**
	 * A design as a project saves it: upgraded, with its defaults written out.
	 * @param raw the design as the request carries it
	 * @param name the project's name, which becomes the design's; {@code null} to keep
	 * its own
	 * @return the design
	 * @throws ApiException {@code 422} with the issues when it is not an ArchSim design
	 */
	ObjectNode normalize(ObjectNode raw, @Nullable String name) {
		ArchitectureDocument design;
		try {
			design = DesignDocuments.load(raw);
		}
		catch (DocumentInvalidException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"The document is not a valid ArchSim document: " + ex.getMessage(), ex.issues());
		}
		ObjectNode json = DesignDocuments.toJson(design);
		if (name != null && !name.isEmpty()) {
			json.withObjectProperty("metadata").put("name", name);
		}
		return json;
	}

	/**
	 * A new, empty design. It pins the newest price snapshot shipped for AWS us-east-1
	 * (generic rates when there is none), as the examples do.
	 * @return the design
	 */
	ObjectNode blank() {
		ObjectNode design = NODES.objectNode();
		design.put("schemaVersion", ArchitectureDocument.SCHEMA_VERSION);
		design.put("kind", ArchitectureDocument.KIND);
		design.putObject("metadata").put("name", "Untitled architecture");
		ObjectNode architecture = design.putObject("architecture");
		architecture.putArray("nodes");
		architecture.putArray("edges");
		design.putObject("workload");
		design.putArray("scenarios");
		design.putObject("layout").putObject("nodes");
		design.set("pricing", this.prices.defaultPricing());
		return design;
	}

}
