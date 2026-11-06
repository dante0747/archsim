package io.archsim.engine.imports;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.TerraformParts.Cloud;

/**
 * The Google Cloud resources a Terraform plan's parts come from: Cloud SQL, Memorystore,
 * Pub/Sub, GKE clusters and Cloud Run.
 */
final class GoogleResources {

	private final TerraformParts parts;

	/**
	 * Reads Google Cloud resources into parts.
	 * @param parts the parts so far
	 */
	GoogleResources(TerraformParts parts) {
		this.parts = parts;
	}

	/**
	 * How each Google Cloud resource type is read.
	 * @return each type's reader
	 */
	Map<String, Consumer<TerraformResource>> readers() {
		Map<String, Consumer<TerraformResource>> readers = new HashMap<>();
		readers.put("google_sql_database_instance", this::cloudSql);
		readers.put("google_redis_instance",
				(resource) -> this.parts.add(resource, "cache", new TerraformConfig().put("technology", "redis")
					.put("memoryGib", resource.values().path("memory_size_gb")), "Memorystore"));
		readers.put("google_pubsub_topic", (resource) -> this.parts.add(resource, "message_queue",
				new TerraformConfig().put("technology", "pubsub"), "Pub/Sub"));
		// a subscription reads a topic, which is the part
		readers.put("google_pubsub_subscription", (resource) -> {
		});
		readers.put("google_container_cluster",
				(resource) -> this.parts.addCluster(resource, new TerraformConfig().put("technology", "gke"), "GKE"));
		readers.put("google_cloud_run_v2_service", this::cloudRun);
		readers.put("google_cloud_run_service", this::cloudRun);
		return readers;
	}

	private void cloudSql(TerraformResource resource) {
		ObjectNode values = resource.values();
		JsonNode tier = TerraformResource.first(values.path("settings")).path("tier");
		TerraformConfig config = new TerraformConfig().put("technology",
				TerraformRules.databaseEngine(values.path("database_version")).orElse("generic"));
		this.parts.sized(config, tier, Cloud.GCP);
		this.parts.add(resource, "database", config,
				"Cloud SQL " + PythonValues.str(values.path("database_version")) + " " + PythonValues.str(tier));
	}

	private void cloudRun(TerraformResource resource) {
		this.parts.add(resource, "service", new TerraformConfig().put("platform", "serverless"), "Cloud Run");
	}

}
