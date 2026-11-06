package io.archsim.engine.imports;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * The technologies a Terraform resource's words name: a database engine
 * ({@code aurora-postgresql}, {@code POSTGRES_15}) and a function's runtime
 * ({@code python3.12}), each found by the first pattern that matches.
 */
final class TerraformRules {

	private static final List<Map.Entry<Pattern, String>> DATABASE_ENGINES = List.of(
			rule("aurora-postgres|^postgres", "postgresql"), rule("aurora-mysql|^aurora$|^mysql", "mysql"),
			rule("^mariadb", "mariadb"), rule("^sqlserver", "sqlserver"), rule("^oracle", "oracle"),
			rule("^POSTGRES", "postgresql"), rule("^MYSQL", "mysql"), rule("^SQLSERVER", "sqlserver"));

	private static final List<Map.Entry<Pattern, String>> RUNTIMES = List.of(rule("^nodejs", "nodejs"),
			rule("^python", "python"), rule("^java", "java"), rule("^go|provided", "go"), rule("^dotnet", "dotnet"),
			rule("^ruby", "ruby"));

	private TerraformRules() {
	}

	/**
	 * The database technology an engine's name names.
	 * @param engine the engine, as written
	 * @return the technology; empty for none, or an engine no pattern matches
	 * @throws ImportShapeException in Python's words for an engine that is not text
	 */
	static Optional<String> databaseEngine(JsonNode engine) {
		return match(DATABASE_ENGINES, engine);
	}

	/**
	 * The technology a function's runtime names.
	 * @param runtime the runtime, as written
	 * @return the technology; empty for none, or a runtime no pattern matches
	 * @throws ImportShapeException in Python's words for a runtime that is not text
	 */
	static Optional<String> runtime(JsonNode runtime) {
		return match(RUNTIMES, runtime);
	}

	/**
	 * The technology of the first rule whose pattern a value contains.
	 * @param rules the rules, in order
	 * @param value the value
	 * @return the technology; empty when the value counts as false or no rule matches
	 */
	private static Optional<String> match(List<Map.Entry<Pattern, String>> rules, JsonNode value) {
		if (!PythonValues.truthy(value)) {
			return Optional.empty();
		}
		String text = PythonValues.matchedText(value);
		return rules.stream().filter((rule) -> rule.getKey().matcher(text).find()).map(Map.Entry::getValue).findFirst();
	}

	private static Map.Entry<Pattern, String> rule(String pattern, String technology) {
		// $ matches before a final newline only, as Python's does
		return Map.entry(Pattern.compile(pattern, Pattern.UNIX_LINES), technology);
	}

}
