package io.archsim.engine.export;

import java.util.Map;
import java.util.Optional;

import io.archsim.engine.load.ComponentFamily;

/**
 * Container images, ports and deployment hints by technology, shared by the exports, and
 * the technology a part of each kind is deployed as when it names none.
 */
final class TechnologySpecs {

	private static final Map<String, TechnologySpec> BY_TECHNOLOGY = Map.ofEntries(
			Map.entry("postgresql",
					TechnologySpec.image("postgres:16", 5432)
						.storingIn("/var/lib/postgresql/data")
						.withEnvironment("POSTGRES_PASSWORD", "change-me")),
			Map.entry("mysql",
					TechnologySpec.image("mysql:8.4", 3306)
						.storingIn("/var/lib/mysql")
						.withEnvironment("MYSQL_ROOT_PASSWORD", "change-me")),
			Map.entry("mariadb",
					TechnologySpec.image("mariadb:11", 3306)
						.storingIn("/var/lib/mysql")
						.withEnvironment("MARIADB_ROOT_PASSWORD", "change-me")),
			Map.entry("sqlserver",
					TechnologySpec.image("mcr.microsoft.com/mssql/server:2022-latest", 1433)
						.storingIn("/var/opt/mssql")
						.withEnvironment("ACCEPT_EULA", "Y", "MSSQL_SA_PASSWORD", "Change-me-123")),
			Map.entry("oracle",
					TechnologySpec.image("gvenzl/oracle-free:23-slim", 1521)
						.storingIn("/opt/oracle/oradata")
						.withEnvironment("ORACLE_PASSWORD", "change-me")
						.noting("community image; check licensing")),
			Map.entry("cockroachdb",
					TechnologySpec.image("cockroachdb/cockroach:v24.2.4", 26257)
						.storingIn("/cockroach/cockroach-data")
						.withCommand("start-single-node", "--insecure")),
			Map.entry("yugabytedb",
					TechnologySpec.image("yugabytedb/yugabyte:latest", 5433)
						.storingIn("/root/var")
						.withCommand("bin/yugabyted", "start", "--background=false")),
			Map.entry("mongodb", TechnologySpec.image("mongo:7", 27017).storingIn("/data/db")),
			Map.entry("cassandra", TechnologySpec.image("cassandra:5", 9042).storingIn("/var/lib/cassandra")),
			Map.entry("scylladb", TechnologySpec.image("scylladb/scylla:6.1", 9042).storingIn("/var/lib/scylla")),
			Map.entry("dynamodb",
					TechnologySpec.image("amazon/dynamodb-local:latest", 8000)
						.storingIn("/home/dynamodblocal/data")
						.noting("local stand-in for a managed DynamoDB-style table")),
			Map.entry("couchbase",
					TechnologySpec.image("couchbase/server:community", 8091).storingIn("/opt/couchbase/var")),
			Map.entry("elasticsearch",
					TechnologySpec.image("docker.elastic.co/elasticsearch/elasticsearch:8.15.3", 9200)
						.storingIn("/usr/share/elasticsearch/data")
						.withEnvironment("discovery.type", "single-node", "xpack.security.enabled", "false")),
			Map.entry("opensearch",
					TechnologySpec.image("opensearchproject/opensearch:2", 9200)
						.storingIn("/usr/share/opensearch/data")
						.withEnvironment("discovery.type", "single-node", "DISABLE_SECURITY_PLUGIN", "true")),
			Map.entry("redis", TechnologySpec.image("redis:7", 6379)),
			Map.entry("valkey", TechnologySpec.image("valkey/valkey:8", 6379)),
			Map.entry("memcached", TechnologySpec.image("memcached:1.6", 11211)),
			Map.entry("varnish", TechnologySpec.image("varnish:7", 80)),
			Map.entry("cdn", TechnologySpec.managed(443, "CDN edge (managed service)")),
			Map.entry("nginx", TechnologySpec.image("nginx:1.27", 80)),
			Map.entry("haproxy", TechnologySpec.image("haproxy:3.0", 80)),
			Map.entry("envoy", TechnologySpec.image("envoyproxy/envoy:v1.31-latest", 10000)),
			Map.entry("traefik", TechnologySpec.image("traefik:v3.1", 80)),
			Map.entry("kong", TechnologySpec.image("kong:3.7", 8000).withEnvironment("KONG_DATABASE", "off")),
			Map.entry("aws_alb", TechnologySpec.managed(80, "managed load balancer")),
			Map.entry("api_gateway", TechnologySpec.managed(443, "managed API gateway")),
			Map.entry("kafka", TechnologySpec.image("apache/kafka:3.8.0", 9092).storingIn("/var/lib/kafka/data")),
			Map.entry("rabbitmq", TechnologySpec.image("rabbitmq:3-management", 5672).storingIn("/var/lib/rabbitmq")),
			Map.entry("nats", TechnologySpec.image("nats:2", 4222).storingIn("/data")),
			Map.entry("redis_streams", TechnologySpec.image("redis:7", 6379).storingIn("/data")),
			Map.entry("sqs",
					TechnologySpec.image("softwaremill/elasticmq-native:1.6.9", 9324)
						.storingIn("/data")
						.noting("SQS-compatible local stand-in")),
			Map.entry("pubsub", TechnologySpec.managed(443, "managed Pub/Sub-style service")));

	/** What a part of each kind is deployed as when it names no technology of its own. */
	private static final Map<ComponentFamily, String> DEFAULT_BY_FAMILY = Map.of(ComponentFamily.DATABASE, "postgresql",
			ComponentFamily.CACHE, "redis", ComponentFamily.NETWORK, "nginx", ComponentFamily.MESSAGING, "rabbitmq");

	/** The URL scheme of a connection to each technology; {@code http} for any other. */
	private static final Map<String, String> SCHEMES = Map.ofEntries(Map.entry("postgresql", "postgresql"),
			Map.entry("cockroachdb", "postgresql"), Map.entry("yugabytedb", "postgresql"), Map.entry("mysql", "mysql"),
			Map.entry("mariadb", "mysql"), Map.entry("mongodb", "mongodb"), Map.entry("redis", "redis"),
			Map.entry("valkey", "redis"), Map.entry("redis_streams", "redis"), Map.entry("memcached", "memcached"),
			Map.entry("kafka", "kafka"), Map.entry("rabbitmq", "amqp"), Map.entry("nats", "nats"),
			Map.entry("cassandra", "cassandra"), Map.entry("scylladb", "cassandra"));

	private TechnologySpecs() {
	}

	/**
	 * How a technology is deployed.
	 * @param technology the technology
	 * @return its spec; empty for a technology the exports have no image for
	 */
	static Optional<TechnologySpec> of(String technology) {
		return Optional.ofNullable(BY_TECHNOLOGY.get(technology));
	}

	/**
	 * The technology a part of a kind is deployed as when it names none.
	 * @param family the part's kind
	 * @return the technology; empty for a kind that has no default
	 */
	static Optional<String> defaultFor(ComponentFamily family) {
		return Optional.ofNullable(DEFAULT_BY_FAMILY.get(family));
	}

	/**
	 * The URL scheme of a connection to a technology.
	 * @param technology the technology
	 * @return its scheme, such as {@code postgresql}; {@code http} for one without its
	 * own
	 */
	static String scheme(String technology) {
		return SCHEMES.getOrDefault(technology, "http");
	}

}
