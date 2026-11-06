package io.archsim.engine.imports;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.validation.DocumentValidator;

/**
 * Designs from what teams already have: a docker-compose file, Kubernetes manifests or a
 * Terraform plan, read as a design and validated. The import report carries what
 * validation found, for the user to fix before a run.
 */
public final class DesignImporter {

	private final ComposeImport compose;

	private final KubernetesImport kubernetes;

	private final TerraformImport terraform;

	private final DocumentValidator validator;

	/**
	 * Imports designs made of a registry's types, validated against its prices.
	 * @param registry the component types
	 * @param prices the price snapshots validation checks instance types against
	 */
	public DesignImporter(ComponentRegistry registry, PriceSnapshots prices) {
		this.compose = new ComposeImport(registry);
		this.kubernetes = new KubernetesImport(registry);
		this.terraform = new TerraformImport(registry);
		this.validator = new DocumentValidator(registry, prices);
	}

	/**
	 * A design from infrastructure text, validated: its JSON with every field written,
	 * and the import report with the issues validation found.
	 * @param format what the text is
	 * @param text the text
	 * @param name the design's name; {@code null} or empty for the text's own
	 * @return the design and the report
	 * @throws ImportRefusedException when the text is not the format, in the Python
	 * engine's words
	 * @throws DocumentInvalidException when what the text makes cannot be read as a
	 * design
	 */
	public ImportedDesign importDesign(ImportFormat format, String text, @Nullable String name) {
		ImportedDesign built = switch (format) {
			case COMPOSE -> this.compose.read(text, name);
			case K8S -> this.kubernetes.read(text, name);
			case TERRAFORM -> this.terraform.read(text, name);
		};
		ArchitectureDocument document = DesignDocuments.load(built.document());
		List<ObjectNode> issues = this.validator.validate(document).issues().stream().map(Issue::toJson).toList();
		return new ImportedDesign(DesignDocuments.toJson(document), built.report().withIssues(issues));
	}

}
