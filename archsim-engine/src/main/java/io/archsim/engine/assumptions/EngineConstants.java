package io.archsim.engine.assumptions;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.cost.CostEstimator;
import io.archsim.engine.cost.GenericRates;
import io.archsim.engine.pricing.Busy;
import io.archsim.engine.sizing.Sizing;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.validation.DocumentValidator;

/**
 * The engine-wide constants behind sizing, prices and the component models, read from the
 * code that uses them.
 */
public final class EngineConstants {

	private static final String DYNAMO_GUIDE = "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/";

	private static final String DYNAMO_PARTITIONS = DYNAMO_GUIDE + "bp-partition-key-design.html";

	private static final String DYNAMO_ON_DEMAND = DYNAMO_GUIDE
			+ "on-demand-capacity-mode.html#on-demand-capacity-mode-initial";

	private static final String DYNAMO_BURST = DYNAMO_GUIDE + "burst-adaptive-capacity.html";

	private static final String KAFKA_RETENTION = "https://kafka.apache.org/documentation/#brokerconfigs_log.retention.hours";

	private static final String LAMBDA_MEMORY = "https://docs.aws.amazon.com/lambda/latest/dg/configuration-memory.html";

	private static final String RDS_CONNECTIONS = "https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/"
			+ "CHAP_Limits.html#RDS_Limits.MaxConnections";

	private static final String ALB_LCU = "https://aws.amazon.com/elasticloadbalancing/pricing/";

	private static final String SQS_PRICING = "https://aws.amazon.com/sqs/pricing/";

	private static final String T4G_BASELINE = "https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/"
			+ "burstable-credits-baseline-concepts.html";

	private static final double PERCENT = 100;

	private static final double SECONDS_PER_MINUTE = 60;

	private static final double SECONDS_PER_HOUR = 3600;

	private static final Area SIZING = new Area("Auto-sizing");

	private static final Area COST = new Area("Cost estimate");

	private static final Area DATABASE = new Area("Database model");

	private static final Area CACHE = new Area("Cache model");

	private static final Area LIMITS = new Area("Limits");

	private static final List<EngineConstant> ALL = List.of(
			SIZING.of("CPU utilisation a part is sized for at its peak", percent(Sizing.TARGET_CPU),
					"sizing.TARGET_CPU"),
			SIZING.of("CPU at the peak a size below the target's may run at, rather than double the cores",
					percent(Sizing.CPU_STEP_SLACK), "sizing.CPU_STEP_SLACK"),
			SIZING.of("Memory headroom over what the model needs", "+" + percent(Sizing.MEMORY_HEADROOM - 1),
					"sizing.MEMORY_HEADROOM"),
			SIZING.of("Storage kept under this fill level", percent(Sizing.STORAGE_FILL), "sizing.STORAGE_FILL"),
			SIZING.of("Smallest auto storage", number(Sizing.MIN_STORAGE_GIB) + " GiB", "sizing.MIN_STORAGE_GIB"),
			SIZING.of("Largest auto memory of one service replica", Sizing.MAX_SERVICE_MEMORY_GIB + " GiB",
					"sizing.MAX_SERVICE_MEMORY_GIB"),
			SIZING.of("Load balancer throughput per vCPU", grouped(Sizing.PROXY_RPS_PER_VCPU) + " req/s",
					"sizing.PROXY_RPS_PER_VCPU"),
			SIZING.of("Load balancer memory per vCPU", Sizing.PROXY_GIB_PER_VCPU + " GiB", "sizing.PROXY_GIB_PER_VCPU"),
			SIZING.of("Broker throughput per vCPU", grouped(Sizing.BROKER_MSGS_PER_VCPU) + " messages/s",
					"sizing.BROKER_MSGS_PER_VCPU"),
			SIZING.of("Broker memory per vCPU", Sizing.BROKER_GIB_PER_VCPU + " GiB", "sizing.BROKER_GIB_PER_VCPU"),
			SIZING.of("Share of a database's data read often, when no hot working set is set",
					percent(Sizing.DB_HOT_SHARE), "sizing.DB_HOT_SHARE"),
			SIZING.of("Smallest auto memory of a database instance", number(Sizing.DB_MIN_MEMORY_GIB) + " GiB",
					"sizing.DB_MIN_MEMORY_GIB"),
			SIZING.of("Database memory per connection", grouped(Sizing.DB_BYTES_PER_CONNECTION) + " bytes",
					"sizing.DB_BYTES_PER_CONNECTION", RDS_CONNECTIONS),
			SIZING.of("A burstable type's CPU at the base load, at most",
					percent(Busy.BURST_BASE_SHARE) + " of its baseline", "pricing.BURST_BASE_SHARE", T4G_BASELINE),
			SIZING.of("A burstable type's CPU at the peak, at most", percent(Busy.BURST_PEAK_SHARE) + " of its vCPUs",
					"pricing.BURST_PEAK_SHARE"),
			SIZING.of("Memory of a VM's operating system", number(Sizing.OS_MEMORY_GIB) + " GiB",
					"sizing.OS_MEMORY_GIB"),
			SIZING.of("Serverless function memory per vCPU", grouped(Sizing.LAMBDA_MB_PER_VCPU) + " MB",
					"sizing.LAMBDA_MB_PER_VCPU", LAMBDA_MEMORY),
			SIZING.of("Backlog a delete-on-ack broker keeps room for",
					number(Math.floor(Sizing.BACKLOG_S / SECONDS_PER_MINUTE)) + " minutes of peak publishes",
					"sizing.BACKLOG_S"),
			SIZING.of("Log retention of Kafka-style brokers",
					number(Math.floor(Sizing.LOG_RETENTION_S / SECONDS_PER_HOUR)) + " hours", "sizing.LOG_RETENTION_S",
					KAFKA_RETENTION),
			COST.of("Hours in a month", String.valueOf(CostEstimator.HOURS_PER_MONTH), "cost.HOURS_PER_MONTH"),
			COST.of("Price per vCPU-hour (parts without a price)", "$" + number(GenericRates.VCPU_HOUR),
					"cost.PRICE_PER_VCPU_HOUR"),
			COST.of("Price per GiB-hour of memory", "$" + number(GenericRates.GIB_HOUR), "cost.PRICE_PER_GIB_HOUR"),
			COST.of("Price per GiB-month of storage", "$" + number(GenericRates.STORAGE_GIB_MONTH),
					"cost.PRICE_PER_STORAGE_GIB_MONTH"),
			COST.of("Bytes an application load balancer processes per request",
					number(CostEstimator.ALB_KB_PER_REQUEST) + " KB", "cost.ALB_KB_PER_REQUEST"),
			COST.of("Processed bytes per load balancer capacity unit (LCU)",
					number(CostEstimator.ALB_GB_PER_LCU_HOUR) + " GB an hour", "cost.ALB_GB_PER_LCU_HOUR", ALB_LCU),
			COST.of("SQS requests per message", CostEstimator.SQS_REQUESTS_PER_MESSAGE + " (send, receive, delete)",
					"cost.SQS_REQUESTS_PER_MESSAGE", SQS_PRICING),
			COST.of("Share of an implied Kubernetes node allocatable to pods",
					percent(CostEstimator.KUBERNETES_ALLOCATABLE), "cost.KUBERNETES_ALLOCATABLE"),
			DATABASE.of("Share of memory used as buffer cache", percent(DatabaseConfig.BUFFER_CACHE_SHARE),
					"database.BUFFER_CACHE_SHARE"),
			DATABASE.of("DynamoDB read units per partition per second", grouped(DatabaseConfig.PARTITION_READ_UNITS),
					"database.PARTITION_READ_UNITS", DYNAMO_PARTITIONS),
			DATABASE.of("DynamoDB write units per partition per second", grouped(DatabaseConfig.PARTITION_WRITE_UNITS),
					"database.PARTITION_WRITE_UNITS", DYNAMO_PARTITIONS),
			DATABASE.of("DynamoDB data per partition", number(DatabaseConfig.PARTITION_GIB) + " GiB",
					"database.PARTITION_GIB"),
			DATABASE.of("DynamoDB on-demand table: reads per second at first",
					grouped(DatabaseConfig.ON_DEMAND_READ_UNITS), "database.ON_DEMAND_READ_UNITS", DYNAMO_ON_DEMAND),
			DATABASE.of("DynamoDB on-demand table: writes per second at first",
					grouped(DatabaseConfig.ON_DEMAND_WRITE_UNITS), "database.ON_DEMAND_WRITE_UNITS", DYNAMO_ON_DEMAND),
			DATABASE.of("DynamoDB burst capacity banked",
					number(DatabaseConfig.BURST_SECONDS) + " s of unused capacity", "database.BURST_SECONDS",
					DYNAMO_BURST),
			CACHE.of("Share of a node's memory that holds keys", percent(CacheConfig.KEY_MEMORY_SHARE),
					"cache.KEY_MEMORY_SHARE"),
			LIMITS.of("Most component calls one client request may cause",
					grouped(DocumentValidator.DEFAULT_MAX_AMPLIFICATION) + " (a warning above a tenth)",
					"validation.DEFAULT_MAX_AMPLIFICATION"));

	private EngineConstants() {
	}

	/**
	 * The constants, in the page's order.
	 * @return every engine-wide constant the page lists
	 */
	public static List<EngineConstant> all() {
		return ALL;
	}

	private static String percent(double share) {
		return PythonFormat.format(share * PERCENT, "g") + "%";
	}

	private static String number(double value) {
		return PythonFormat.format(value, "g");
	}

	private static String grouped(double value) {
		return PythonFormat.format(value, ",g");
	}

	private static String grouped(long value) {
		return PythonFormat.format(value, ",");
	}

	/**
	 * The model a constant belongs to.
	 *
	 * @param name its name on the page
	 */
	private record Area(String name) {

		EngineConstant of(String constant, String value, String code) {
			return of(constant, value, code, null);
		}

		EngineConstant of(String constant, String value, String code, @Nullable String source) {
			return new EngineConstant(this.name, constant, value, code, source);
		}

	}

}
