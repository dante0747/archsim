package io.archsim.engine.imports;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.archsim.engine.imports.ImageKind.Use;

/**
 * What a container image is, by its full repository name: a technology's own image or one
 * of its common distributions (Bitnami, Confluent, Timescale, PostGIS, Redis Stack...), a
 * tool that runs next to infrastructure, or an application. Only the technologies' own
 * images are listed, so an exporter, UI or client named after a technology is not taken
 * for it.
 */
final class Images {

	private static final List<Rule> RULES = List.of(
			rule("database", "postgresql",
					names("postgres", "postgresql", "postgis", "timescaledb", "timescaledb-ha", "pgvector",
							"postgresql-repmgr", "crunchy-postgres")),
			rule("database", "mysql", names("mysql", "mysql-server", "percona-server")),
			rule("database", "mariadb", names("mariadb", "mariadb-galera")),
			rule("database", "sqlserver", names("mssql-server-linux", "azure-sql-edge"), "mssql/server"),
			rule("database", "oracle", names("oracle-xe", "oracle-free", "oracle-database"), "oracle/database"),
			rule("database", "cockroachdb", names("cockroach")), rule("database", "yugabytedb", names("yugabyte")),
			rule("database", "mongodb",
					names("mongo", "mongodb", "mongodb-sharded", "mongodb-community-server",
							"mongodb-enterprise-server")),
			rule("database", "cassandra", names("cassandra")), rule("database", "scylladb", names("scylla")),
			rule("database", "dynamodb", names("dynamodb-local")),
			rule("database", "couchbase", names("couchbase"), "couchbase/server"),
			rule("database", "elasticsearch", names("elasticsearch")),
			rule("database", "opensearch", names("opensearch")),
			rule("cache", "redis", names("redis", "redis-stack", "redis-stack-server", "redis-cluster")),
			rule("cache", "valkey", names("valkey", "valkey-cluster")), rule("cache", "memcached", names("memcached")),
			rule("cache", "varnish", names("varnish")),
			rule("message_queue", "kafka", names("kafka", "kafka-native", "cp-kafka", "cp-server", "redpanda")),
			rule("message_queue", "rabbitmq", names("rabbitmq")),
			rule("message_queue", "nats", names("nats", "nats-streaming")),
			rule("message_queue", "sqs", names("elasticmq", "elasticmq-native")),
			rule("load_balancer", "nginx", names("nginx", "nginx-unprivileged", "openresty")),
			rule("load_balancer", "haproxy", names("haproxy", "haproxy-alpine", "haproxy-debian", "haproxy-ubuntu")),
			rule("load_balancer", "envoy", names("envoy", "envoy-alpine", "envoy-distroless")),
			rule("load_balancer", "traefik", names("traefik")),
			rule("load_balancer", "kong", names("kong", "kong-gateway")));

	/** The technologies' words, which a tool's name carries beside a tool's word. */
	private static final Set<String> INFRA_WORDS = Set.of("postgres", "postgresql", "pg", "mysql", "mysqld", "mariadb",
			"mssql", "oracle", "cockroach", "cockroachdb", "yugabyte", "mongo", "mongodb", "cassandra", "scylla",
			"dynamodb", "couchbase", "elasticsearch", "elastic", "opensearch", "redis", "valkey", "memcached",
			"varnish", "kafka", "redpanda", "rabbitmq", "rabbit", "nats", "sqs", "elasticmq", "nginx", "haproxy",
			"envoy", "traefik", "kong");

	private static final Pattern TOOL_WORD = Pattern
		.compile("exporter|ui|gui|express|admin|commander|insight|console|dashboard|manager|monitor|"
				+ "metrics|migrations?|migrator|migrate|consumer|producer|worker|client|cli|tools?|operator|"
				+ "backup|restore|init|setup|seed|seeder|connect|sentinel|proxy|bouncer");

	/** Well-known tools, by their image's name. */
	private static final Set<String> TOOL_IMAGE_NAMES = Set.of("pgadmin", "pgadmin4", "adminer", "phpmyadmin",
			"redisinsight", "akhq", "kafdrop", "cmak", "flyway", "liquibase", "migrate", "pgbouncer", "pgpool",
			"proxysql", "kcat", "kafkacat");

	private static final Pattern EXPORTER = Pattern.compile("[-_]exporter$");

	private static final Pattern WORD_SEPARATOR = Pattern.compile("[-_.]");

	private static final Pattern REGISTRY_HOST = Pattern.compile("[.:]");

	private Images() {
	}

	/**
	 * What a container image is.
	 * @param image the image reference
	 * @return infrastructure (a part type and technology), a tool or an application, and
	 * why
	 */
	static ImageKind classify(String image) {
		String repository = repository(image);
		String name = repository.substring(repository.lastIndexOf('/') + 1);
		if (isTool(name)) {
			return new ImageKind(Use.TOOL, "service", null,
					"looks like a tool that runs next to infrastructure (an exporter, UI, admin "
							+ "console, migration job, client or consumer), not the infrastructure itself");
		}
		for (Rule rule : RULES) {
			if (rule.matches(repository, name)) {
				return new ImageKind(Use.INFRASTRUCTURE, rule.type(), rule.technology(),
						"is a " + rule.technology() + " image (by its name)");
			}
		}
		return new ImageKind(Use.APPLICATION, "service", null, "is not a known infrastructure image");
	}

	/**
	 * The repository of an image reference, in lower case, without registry host, tag or
	 * digest: {@code mcr.microsoft.com/mssql/server:2022} is {@code mssql/server}.
	 * @param image the image reference
	 * @return its repository
	 */
	static String repository(String image) {
		String reference = image.strip().split("@", 2)[0].toLowerCase(Locale.ROOT);
		String[] parts = reference.split("/", -1);
		String last = parts[parts.length - 1];
		if (last.contains(":")) {
			parts[parts.length - 1] = last.substring(0, last.lastIndexOf(':'));
		}
		int first = 0;
		if (parts.length > 1 && (REGISTRY_HOST.matcher(parts[0]).find() || parts[0].equals("localhost"))) {
			first = 1;
		}
		return String.join("/", Arrays.stream(parts, first, parts.length).filter((part) -> !part.isEmpty()).toList());
	}

	/**
	 * Whether an image's name is an exporter, UI, admin console, migration job or client.
	 * @param name the last part of its repository
	 * @return {@code true} for a well-known tool, an exporter, or a technology's word
	 * beside a tool's word
	 */
	private static boolean isTool(String name) {
		if (TOOL_IMAGE_NAMES.contains(name) || EXPORTER.matcher(name).find()) {
			return true;
		}
		List<String> words = List.of(WORD_SEPARATOR.split(name, -1));
		return words.stream().anyMatch(INFRA_WORDS::contains)
				&& words.stream().anyMatch((word) -> TOOL_WORD.matcher(word).matches());
	}

	private static Set<String> names(String... names) {
		return Set.of(names);
	}

	private static Rule rule(String type, String technology, Set<String> names, String... paths) {
		return new Rule(type, technology, names, List.of(paths));
	}

	/**
	 * A technology's images: by the last part of the repository, or by the repository's
	 * end.
	 *
	 * @param type the part type
	 * @param technology the technology
	 * @param names the names the repository's last part may be
	 * @param paths the paths the repository may end with
	 */
	private record Rule(String type, String technology, Set<String> names, List<String> paths) {

		boolean matches(String repository, String name) {
			return this.names.contains(name) || this.paths.stream()
				.anyMatch((path) -> repository.equals(path) || repository.endsWith("/" + path));
		}

	}

}
