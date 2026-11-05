package io.archsim.engine.technology;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.catalog.Preset;
import io.archsim.model.component.ComponentRegistry;

/**
 * How the engine names a technology ({@code technology_label}): {@code postgresql} is
 * PostgreSQL. A technology the engine knows has its own name, else its type's preset's,
 * else its words capitalized.
 */
public final class TechnologyNames {

	/** The technologies the engine knows by name. */
	private static final Map<String, String> NAMES = Map.ofEntries(Map.entry("postgresql", "PostgreSQL"),
			Map.entry("mysql", "MySQL"), Map.entry("mariadb", "MariaDB"), Map.entry("sqlserver", "SQL Server"),
			Map.entry("oracle", "Oracle"), Map.entry("cockroachdb", "CockroachDB"),
			Map.entry("yugabytedb", "YugabyteDB"), Map.entry("mongodb", "MongoDB"), Map.entry("cassandra", "Cassandra"),
			Map.entry("scylladb", "ScyllaDB"), Map.entry("dynamodb", "DynamoDB-style"),
			Map.entry("couchbase", "Couchbase"), Map.entry("elasticsearch", "Elasticsearch"),
			Map.entry("opensearch", "OpenSearch"), Map.entry("redis", "Redis"), Map.entry("valkey", "Valkey"),
			Map.entry("memcached", "Memcached"), Map.entry("cdn", "CDN edge"), Map.entry("varnish", "Varnish"),
			Map.entry("nginx", "Nginx"), Map.entry("haproxy", "HAProxy"), Map.entry("envoy", "Envoy"),
			Map.entry("traefik", "Traefik"), Map.entry("kong", "Kong"), Map.entry("aws_alb", "ALB-style"),
			Map.entry("api_gateway", "API gateway"), Map.entry("kafka", "Kafka"), Map.entry("rabbitmq", "RabbitMQ"),
			Map.entry("sqs", "SQS-style"), Map.entry("pubsub", "Pub/Sub-style"), Map.entry("nats", "NATS"),
			Map.entry("redis_streams", "Redis Streams"), Map.entry("java", "Java"), Map.entry("nodejs", "Node.js"),
			Map.entry("go", "Go"), Map.entry("python", "Python"), Map.entry("dotnet", ".NET"),
			Map.entry("rust", "Rust"), Map.entry("php", "PHP"), Map.entry("ruby", "Ruby"),
			Map.entry("kubernetes", "Kubernetes"), Map.entry("eks", "EKS-style"), Map.entry("gke", "GKE-style"),
			Map.entry("aks", "AKS-style"), Map.entry("k3s", "k3s"), Map.entry("openshift", "OpenShift"),
			Map.entry("mobile", "Mobile app"), Map.entry("ios", "iOS"), Map.entry("android", "Android"),
			Map.entry("react_native", "React Native"), Map.entry("flutter", "Flutter"),
			Map.entry("browser", "Web browser"), Map.entry("react", "React"), Map.entry("nextjs", "Next.js"),
			Map.entry("vue", "Vue"), Map.entry("angular", "Angular"), Map.entry("svelte", "Svelte"),
			Map.entry("api_client", "API client"), Map.entry("rest", "REST / OpenAPI"), Map.entry("graphql", "GraphQL"),
			Map.entry("generic", "Generic"));

	private static final Pattern SEPARATORS = Pattern.compile("[_-]+");

	private final ComponentFacts facts;

	/**
	 * Names the technologies of a registry's types.
	 * @param registry the component types
	 */
	public TechnologyNames(ComponentRegistry registry) {
		this.facts = new ComponentFacts(registry);
	}

	/**
	 * A technology's name.
	 * @param type the part's type, whose presets may name it
	 * @param technology the technology
	 * @return its name; empty for an empty technology
	 */
	public Optional<String> of(String type, String technology) {
		if (technology.isEmpty()) {
			return Optional.empty();
		}
		String known = NAMES.get(technology);
		if (known != null) {
			return Optional.of(known);
		}
		return Optional.of(this.facts.type(type)
			.flatMap((facts) -> facts.presets()
				.stream()
				.filter((preset) -> preset.technology().equals(technology))
				.findFirst())
			.map(Preset::name)
			.orElseGet(() -> capitalized(technology)));
	}

	/**
	 * A technology's words, separated by spaces, each starting with a capital, as the
	 * Python engine capitalizes them: a word is a run of letters, numbers and
	 * underscores, which a combining mark ends, as Python's {@code \w} reads a text.
	 * Which characters are letters or numbers, and their upper case, follow the JDK's
	 * Unicode (16.0 in Java 25), newer than Python 3.12's (15.0): a character added since
	 * reads differently. No technology the engine knows has one; a technology a person
	 * types may.
	 * @param technology the technology
	 * @return its words, capitalized
	 */
	private static String capitalized(String technology) {
		String words = SEPARATORS.matcher(technology).replaceAll(" ");
		StringBuilder capitalized = new StringBuilder(words.length());
		boolean inWord = false;
		for (int i = 0; i < words.length(); i += Character.charCount(words.codePointAt(i))) {
			int codePoint = words.codePointAt(i);
			boolean wordCharacter = isWordCharacter(codePoint);
			String character = Character.toString(codePoint);
			capitalized.append((wordCharacter && !inWord) ? character.toUpperCase(Locale.ROOT) : character);
			inWord = wordCharacter;
		}
		return capitalized.toString();
	}

	private static boolean isWordCharacter(int codePoint) {
		if (Character.isLetter(codePoint) || codePoint == '_') {
			return true;
		}
		return switch (Character.getType(codePoint)) {
			case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
			default -> false;
		};
	}

}
