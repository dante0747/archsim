package io.archsim.engine.imports;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.TerraformParts.Cloud;
import io.archsim.engine.numeric.PythonRound;

/**
 * The AWS resources a Terraform plan's parts come from: databases (RDS, Aurora,
 * DynamoDB), caches (ElastiCache, CloudFront), functions (Lambda), queues and streams
 * (SQS, MSK), load balancers and gateways, EKS clusters and ECS services.
 */
final class AwsResources {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The decimals a function's memory is rounded to. */
	private static final int MEMORY_DECIMALS = 3;

	private final TerraformParts parts;

	/**
	 * Reads AWS resources into parts.
	 * @param parts the parts so far
	 */
	AwsResources(TerraformParts parts) {
		this.parts = parts;
	}

	/**
	 * How each AWS resource type is read.
	 * @return each type's reader
	 */
	Map<String, Consumer<TerraformResource>> readers() {
		Map<String, Consumer<TerraformResource>> readers = new HashMap<>();
		readers.put("aws_db_instance", this::dbInstance);
		readers.put("aws_rds_cluster", this::rdsCluster);
		readers.put("aws_elasticache_cluster", this::elastiCache);
		readers.put("aws_elasticache_replication_group", this::elastiCache);
		readers.put("aws_lambda_function", this::lambda);
		readers.put("aws_sqs_queue", this::sqs);
		readers.put("aws_msk_cluster", this::msk);
		readers.put("aws_lb", this::loadBalancer);
		readers.put("aws_alb", this::loadBalancer);
		readers.put("aws_api_gateway_rest_api", this::apiGateway);
		readers.put("aws_apigatewayv2_api", this::apiGateway);
		readers.put("aws_cloudfront_distribution", (resource) -> this.parts.add(resource, "cache",
				new TerraformConfig().put("technology", "cdn").label("CloudFront"), "CDN"));
		readers.put("aws_dynamodb_table", this::dynamoDb);
		readers.put("aws_eks_cluster",
				(resource) -> this.parts.addCluster(resource, new TerraformConfig().put("technology", "eks"), "EKS"));
		readers.put("aws_ecs_service",
				(resource) -> this.parts.add(resource, "service", new TerraformConfig().put("platform", "docker")
					.put("replicas", resource.values().path("desired_count")), "ECS service"));
		return readers;
	}

	/**
	 * An RDS instance; a read replica is counted on its source later.
	 * @param resource the resource
	 */
	private void dbInstance(TerraformResource resource) {
		ObjectNode values = resource.values();
		if (PythonValues.truthy(values.path("replicate_source_db"))) {
			return;
		}
		TerraformConfig config = new TerraformConfig()
			.put("technology", TerraformRules.databaseEngine(values.path("engine")).orElse("generic"))
			.put("storageGib", values.path("allocated_storage"));
		JsonNode instanceClass = values.path("instance_class");
		this.parts.sized(config, instanceClass, Cloud.AWS);
		this.parts.add(resource, "database", config,
				PythonValues.str(values.path("engine")) + " " + PythonValues.str(instanceClass));
	}

	/**
	 * An Aurora cluster, which its instances attach to later.
	 * @param resource the resource
	 */
	private void rdsCluster(TerraformResource resource) {
		ObjectNode values = resource.values();
		TerraformConfig config = new TerraformConfig().put("technology",
				TerraformRules.databaseEngine(values.path("engine")).orElse("generic"));
		String partId = this.parts.add(resource, "database", config, PythonValues.str(values.path("engine")));
		JsonNode name = values.path("cluster_identifier");
		this.parts.cluster(PythonValues.str(PythonValues.truthy(name) ? name : resource.requiredName()), partId);
	}

	private void elastiCache(TerraformResource resource) {
		ObjectNode values = resource.values();
		JsonNode written = values.path("engine");
		String engine = PythonValues.truthy(written) ? PythonValues.str(written) : "redis";
		TerraformConfig config = new TerraformConfig().put("technology", switch (engine) {
			case "memcached", "valkey" -> engine;
			default -> "redis";
		});
		JsonNode shards = values.path("num_node_groups");
		if (PythonValues.isInt(shards) && PythonValues.toInt(shards).compareTo(BigInteger.ONE) > 0) {
			config.put("shards", shards);
		}
		JsonNode nodeType = values.path("node_type");
		this.parts.sized(config, nodeType, Cloud.AWS);
		this.parts.add(resource, "cache", config, engine + " " + PythonValues.str(nodeType));
	}

	private void lambda(TerraformResource resource) {
		ObjectNode values = resource.values();
		JsonNode memory = values.path("memory_size");
		JsonNode concurrency = values.path("reserved_concurrent_executions");
		TerraformConfig config = new TerraformConfig().put("platform", "serverless")
			.put("technology", TerraformRules.runtime(values.path("runtime")).orElse("generic"))
			.put("memoryGib", PythonValues.isNumber(memory) ? gib(memory) : null)
			.put("concurrencyLimit", isPositive(concurrency) ? concurrency : null);
		this.parts.add(resource, "service", config, "Lambda " + PythonValues.str(values.path("runtime")));
	}

	private void sqs(TerraformResource resource) {
		ObjectNode values = resource.values();
		TerraformConfig config = new TerraformConfig().put("technology", "sqs")
			.put("fifo", PythonValues.truthy(values.path("fifo_queue")) ? NODES.booleanNode(true) : null)
			.put("visibilityTimeoutS", values.path("visibility_timeout_seconds"));
		this.parts.add(resource, "message_queue", config, "SQS");
	}

	private void msk(TerraformResource resource) {
		ObjectNode values = resource.values();
		ObjectNode brokers = TerraformResource.first(values.path("broker_node_group_info"));
		TerraformConfig config = new TerraformConfig().put("technology", "kafka")
			.put("brokers", values.path("number_of_broker_nodes"));
		JsonNode instanceType = brokers.path("instance_type");
		this.parts.sized(config, instanceType, Cloud.AWS);
		this.parts.add(resource, "message_queue", config, "MSK " + PythonValues.str(instanceType));
	}

	private void loadBalancer(TerraformResource resource) {
		ObjectNode values = resource.values();
		JsonNode type = values.has("load_balancer_type") ? values.get("load_balancer_type")
				: NODES.stringNode("application");
		boolean application = type.isString() && "application".equals(type.stringValue());
		this.parts.add(resource, "load_balancer",
				new TerraformConfig().put("technology", application ? "aws_alb" : "generic"),
				application ? "ALB" : "NLB (generic: no ALB idle timeout)");
	}

	private void apiGateway(TerraformResource resource) {
		this.parts.add(resource, "load_balancer", new TerraformConfig().put("technology", "api_gateway"),
				"API Gateway");
	}

	private void dynamoDb(TerraformResource resource) {
		ObjectNode values = resource.values();
		JsonNode billing = values.path("billing_mode");
		boolean provisioned = billing.isString() && "PROVISIONED".equals(billing.stringValue());
		TerraformConfig config = new TerraformConfig().put("technology", "dynamodb")
			.put("capacityMode", provisioned ? "provisioned" : "on_demand")
			.put("readCapacityUnits", provisioned ? values.path("read_capacity") : null)
			.put("writeCapacityUnits", provisioned ? values.path("write_capacity") : null);
		this.parts.add(resource, "database", config, "DynamoDB");
	}

	/**
	 * {@code round(mib / 1024, 3)}.
	 * @param mib a number of MiB, not text
	 * @return its GiB, rounded as Python rounds them
	 */
	private static JsonNode gib(JsonNode mib) {
		return NODES.numberNode(PythonRound.round(PythonValues.divided(mib, Quantities.MIB_PER_GIB), MEMORY_DECIMALS));
	}

	/**
	 * {@code (value or 0) > 0}.
	 * @param value the value
	 * @return {@code true} for a number above zero
	 * @throws ImportShapeException in Python's words for a value that counts as true and
	 * is not a number
	 */
	private static boolean isPositive(JsonNode value) {
		if (!PythonValues.truthy(value)) {
			return false;
		}
		if (!PythonValues.isNumber(value)) {
			throw new ImportShapeException(
					"'>' not supported between instances of '" + PythonValues.type(value) + "' and 'int'");
		}
		// exactly: a whole number beyond a float's range is still above zero
		return value.isFloatingPointNumber() ? value.doubleValue() > 0 : PythonValues.toInt(value).signum() > 0;
	}

}
