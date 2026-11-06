package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.ComponentRegistry;

/**
 * A design from {@code terraform show -json} output: a plan or a state. Resources that
 * map reliably become parts: databases (engine and instance class become the technology
 * and real CPU and memory), caches, serverless functions, queues and streams, load
 * balancers and gateways, CDNs, Kubernetes clusters and node pools, and container
 * services, on AWS, GCP and Azure. Links come where the code states them; a load balancer
 * and a CDN get guessed ones, and every other pair is a suggestion.
 */
public final class TerraformImport {

	/** What the text must be, in the words of a refusal. */
	static final String SHAPE = "`terraform show -json` output";

	/** Resources read in the second pass, attached to parts made from other resources. */
	private static final Set<String> ATTACHED = Set.of("aws_rds_cluster_instance", "aws_eks_node_group",
			"google_container_node_pool", "aws_lambda_event_source_mapping");

	private final ComponentRegistry registry;

	/**
	 * Imports designs made of a registry's types.
	 * @param registry the component types
	 */
	public TerraformImport(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * A design from a Terraform plan or state, before validation, with the import report.
	 * @param text the output of {@code terraform show -json}
	 * @param name the design's name; {@code null} or empty for the default
	 * @return the design and the report
	 * @throws ImportRefusedException when the text is not JSON, holds no resources, or is
	 * not output the import can read
	 */
	public ImportedDesign read(String text, @Nullable String name) {
		try {
			return terraform(text, name);
		}
		catch (ImportShapeException ex) {
			throw new ImportRefusedException("this is not " + SHAPE + " the import can read (" + ex.getMessage() + ")",
					ex);
		}
	}

	private ImportedDesign terraform(String text, @Nullable String name) {
		ObjectNode plan = PythonMappings.mapping(TerraformJson.read(text));
		List<TerraformResource> resources = resources(rootModule(plan));
		if (resources.isEmpty()) {
			throw new ImportRefusedException(
					"no resources found (expected `terraform show -json` output with planned_values or values)");
		}
		ImportLog log = new ImportLog("Terraform");
		String title = (name != null && !name.isEmpty()) ? name : "Imported from Terraform";
		DesignBuilder design = new DesignBuilder(this.registry, JsonNodeFactory.instance.stringNode(title),
				"a Terraform plan");
		TerraformParts parts = new TerraformParts(design, log);
		Map<String, Consumer<TerraformResource>> readers = readers(parts);
		for (TerraformResource resource : resources) {
			readResource(readers, parts, resource);
		}
		new TerraformAttachments(parts).attach(resources);
		TerraformLinks links = new TerraformLinks(parts);
		links.linkEventSources(resources, TerraformReferences.of(plan));
		links.guessLinks();
		List<String> entries = links.entries();
		new ImportFinish(design, log).finish();
		new EntryPoint(design, log, Set.of()).addClients(entries);
		return new ImportedDesign(design.document(), log.report());
	}

	/**
	 * The module a plan's (or else a state's) resources are in.
	 * @param plan the plan
	 * @return its root module, {@code value or {}}
	 */
	private static JsonNode rootModule(ObjectNode plan) {
		JsonNode planned = plan.path("planned_values");
		JsonNode values = PythonValues.truthy(planned) ? planned : plan.path("values");
		JsonNode root = PythonMappings.dict(values, "get").path("root_module");
		return PythonValues.truthy(root) ? root : JsonNodeFactory.instance.objectNode();
	}

	/**
	 * The resources a module and its child modules manage, data sources left out.
	 * @param value the module
	 * @return the resources, the module's before its children's
	 */
	private static List<TerraformResource> resources(JsonNode value) {
		ObjectNode module = PythonMappings.mapping(value);
		List<TerraformResource> resources = new ArrayList<>();
		for (JsonNode item : PythonValues.items(module.path("resources"))) {
			TerraformResource resource = TerraformResource.of(item);
			if (resource.isManaged()) {
				resources.add(resource);
			}
		}
		PythonValues.items(module.path("child_modules")).forEach((child) -> resources.addAll(resources(child)));
		return resources;
	}

	private static Map<String, Consumer<TerraformResource>> readers(TerraformParts parts) {
		Map<String, Consumer<TerraformResource>> readers = new HashMap<>(new AwsResources(parts).readers());
		readers.putAll(new GoogleResources(parts).readers());
		readers.putAll(new AzureResources(parts).readers());
		return readers;
	}

	/**
	 * Reads a resource in the first pass: into its part, or listed as ignored unless it
	 * attaches to another's part in the second.
	 * @param readers how each type is read
	 * @param parts the parts so far
	 * @param resource the resource
	 */
	private static void readResource(Map<String, Consumer<TerraformResource>> readers, TerraformParts parts,
			TerraformResource resource) {
		JsonNode type = resource.type();
		if (!type.isString()) {
			// Python compares the type with each name, then looks it up in a set, where a
			// list or a mapping cannot be looked up
			if (type.isContainer()) {
				throw PythonValues.unhashable(type);
			}
			parts.ignore(resource);
			return;
		}
		Consumer<TerraformResource> reader = readers.get(type.stringValue());
		if (reader != null) {
			reader.accept(resource);
		}
		else if (!ATTACHED.contains(type.stringValue())) {
			parts.ignore(resource);
		}
	}

}
