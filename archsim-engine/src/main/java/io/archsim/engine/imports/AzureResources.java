package io.archsim.engine.imports;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.TerraformParts.Cloud;
import io.archsim.engine.numeric.PythonNumbers;

/**
 * The Azure resources a Terraform plan's parts come from: flexible servers, Azure Cache
 * for Redis, AKS clusters, Azure Functions, Service Bus and Application Gateway.
 */
final class AzureResources {

	private final TerraformParts parts;

	/**
	 * Reads Azure resources into parts.
	 * @param parts the parts so far
	 */
	AzureResources(TerraformParts parts) {
		this.parts = parts;
	}

	/**
	 * How each Azure resource type is read.
	 * @return each type's reader
	 */
	Map<String, Consumer<TerraformResource>> readers() {
		Map<String, Consumer<TerraformResource>> readers = new HashMap<>();
		readers.put("azurerm_postgresql_flexible_server", (resource) -> flexibleServer(resource, "postgresql"));
		readers.put("azurerm_mysql_flexible_server", (resource) -> flexibleServer(resource, "mysql"));
		readers.put("azurerm_redis_cache", (resource) -> this.parts.add(resource, "cache",
				new TerraformConfig().put("technology", "redis"), "Azure Cache for Redis"));
		readers.put("azurerm_kubernetes_cluster", this::aks);
		readers.put("azurerm_linux_function_app", this::functions);
		readers.put("azurerm_windows_function_app", this::functions);
		readers.put("azurerm_servicebus_queue", this::serviceBus);
		readers.put("azurerm_servicebus_topic", this::serviceBus);
		readers.put("azurerm_application_gateway", (resource) -> this.parts.add(resource, "load_balancer",
				new TerraformConfig(), "Application Gateway (generic)"));
		return readers;
	}

	private void functions(TerraformResource resource) {
		this.parts.add(resource, "service", new TerraformConfig().put("platform", "serverless"), "Azure Functions");
	}

	private void serviceBus(TerraformResource resource) {
		this.parts.add(resource, "message_queue", new TerraformConfig(), "Service Bus (generic queue)");
	}

	/**
	 * A flexible server: its storage, and its size by its SKU.
	 * @param resource the resource
	 * @param technology {@code postgresql} or {@code mysql}
	 */
	private void flexibleServer(TerraformResource resource, String technology) {
		ObjectNode values = resource.values();
		JsonNode storage = PythonValues.truthy(values.path("storage_mb")) ? storageGib(values.get("storage_mb")) : null;
		TerraformConfig config = new TerraformConfig().put("technology", technology).put("storageGib", storage);
		JsonNode sku = values.path("sku_name");
		this.parts.sized(config, sku, Cloud.AZURE);
		this.parts.add(resource, "database", config, "Azure flexible server " + PythonValues.str(sku));
	}

	/**
	 * An AKS cluster: its default node pool's size, count and autoscaling.
	 * @param resource the resource
	 */
	private void aks(TerraformResource resource) {
		ObjectNode pool = TerraformResource.first(resource.values().path("default_node_pool"));
		JsonNode count = pool.path("node_count");
		TerraformConfig config = new TerraformConfig().put("technology", "aks")
			.put("nodes", PythonValues.truthy(count) ? count : pool.path("min_count"));
		JsonNode vmSize = pool.path("vm_size");
		InstanceSizes.azure(vmSize).ifPresent((size) -> {
			config.put("nodeVcpu", size.cpu()).put("nodeMemoryGib", size.memory());
			config.derived("from VM size " + PythonValues.str(vmSize) + " (" + Cloud.AZURE.docs() + ")", "nodeVcpu",
					"nodeMemoryGib");
		});
		if (PythonValues.truthy(pool.path("enable_auto_scaling"))
				|| PythonValues.truthy(pool.path("auto_scaling_enabled"))) {
			config.put("clusterAutoscaler", true)
				.put("minNodes", pool.path("min_count"))
				.put("maxNodes", pool.path("max_count"));
		}
		this.parts.addCluster(resource, config, "AKS " + PythonValues.str(vmSize));
	}

	/**
	 * {@code round(mib / 1024)}: whole GiB, halves to even.
	 * @param mib a number of MiB
	 * @return the whole GiB
	 * @throws ImportShapeException in Python's words for a value that is not a number
	 * @throws ImportRefusedException in Python's words for a quotient that is not a
	 * finite float
	 */
	private static JsonNode storageGib(JsonNode mib) {
		if (!PythonValues.isNumber(mib)) {
			throw new ImportShapeException(
					"unsupported operand type(s) for /: '" + PythonValues.type(mib) + "' and 'int'");
		}
		double gib = PythonValues.divided(mib, Quantities.MIB_PER_GIB);
		try {
			return PythonValues.number(PythonNumbers.round(gib));
		}
		catch (NumberFormatException ex) {
			throw new ImportRefusedException(String.valueOf(ex.getMessage()), ex);
		}
	}

}
