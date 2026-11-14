package io.archsim.model.upgrade;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Documents of older schema versions are upgraded step by step; anything else is refused
 * with a reason.
 */
class UpgradesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static ObjectNode document(String version) {
		return (ObjectNode) JSON.readTree("{\"schemaVersion\": \"%s\", \"workload\": {\"rps\": 5}}".formatted(version));
	}

	@Test
	void chain_fromOnePointZero_reachesTheCurrentVersionWithoutGaps() {
		List<Upgrade> chain = Upgrades.CHAIN;
		assertThat(chain.getFirst().from()).isEqualTo("1.0");
		assertThat(chain.getLast().to()).isEqualTo(ArchitectureDocument.SCHEMA_VERSION);
		for (int i = 1; i < chain.size(); i++) {
			assertThat(chain.get(i).from()).isEqualTo(chain.get(i - 1).to());
		}
	}

	@Test
	void toCurrent_olderDocument_upgradesOneStepAtATimeWithANoteForEach() {
		Upgraded upgraded = Upgrades.toCurrent(document("1.5"));
		assertThat(upgraded.notes()).containsExactly("migrated schema 1.5 -> 1.6", "migrated schema 1.6 -> 1.7",
				"migrated schema 1.7 -> 1.8");
		assertThat(upgraded.document().get("schemaVersion").stringValue()).isEqualTo("1.8");
		assertThat(upgraded.document().get("workload")).isEqualTo(JSON.readTree("{\"rps\": 5}"));
	}

	@Test
	void toCurrent_currentDocument_isLeftAsItIs() {
		Upgraded upgraded = Upgrades.toCurrent(document("1.8"));
		assertThat(upgraded.notes()).isEmpty();
		assertThat(upgraded.document()).isEqualTo(document("1.8"));
	}

	@Test
	void toCurrent_aSchemaOnePointSevenDocument_changesOnlyItsVersion() {
		Upgraded upgraded = Upgrades.toCurrent(document("1.7"));

		assertThat(upgraded.document()).isEqualTo(document("1.8"));
	}

	@Test
	void toCurrent_anyInput_isNeverChanged() {
		ObjectNode input = document("1.0");
		Upgrades.toCurrent(input);
		assertThat(input).isEqualTo(document("1.0"));
	}

	@Test
	void toCurrent_versionWrittenAsNumber_isReadAsPythonWritesIt() {
		JsonNode input = JSON.readTree("{\"schemaVersion\": 1.6}");
		assertThat(Upgrades.toCurrent(input).notes()).containsExactly("migrated schema 1.6 -> 1.7",
				"migrated schema 1.7 -> 1.8");
	}

	@Test
	void upgrade_stepThatChangesTheDocument_isAppliedInTurn() {
		List<Upgrade> chain = List.of(new Upgrade("2.0", "2.1", "renames rps", (doc) -> {
			doc.set("load", doc.remove("rps"));
			return doc;
		}));
		Upgraded upgraded = Upgrades.upgrade(JSON.readTree("{\"schemaVersion\": \"2.0\", \"rps\": 5}"), "2.1", chain);
		assertThat(upgraded.document()).isEqualTo(JSON.readTree("{\"schemaVersion\": \"2.1\", \"load\": 5}"));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
			[]                         | an architecture document must be a JSON object
			{}                         | missing 'schemaVersion'; is this an ArchSim architecture document?
			{"schemaVersion": null}    | missing 'schemaVersion'; is this an ArchSim architecture document?
			{"schemaVersion": "one"}   | 'one' is not a valid schemaVersion
			{"schemaVersion": ""}      | '' is not a valid schemaVersion
			{"schemaVersion": true}    | 'True' is not a valid schemaVersion
			{"schemaVersion": "0.9"}   | no migration path from schema 0.9 to 1.8
			{"schemaVersion": "1.3.0"} | no migration path from schema 1.3.0 to 1.8
			""")
	void toCurrent_documentItCannotUpgrade_isRefusedSayingWhy(String input, String message) {
		assertThatThrownBy(() -> Upgrades.toCurrent(JSON.readTree(input))).isInstanceOf(UpgradeException.class)
			.hasMessage(message);
	}

	@ParameterizedTest(name = "schema {0}")
	@ValueSource(strings = { "1.10", "1.8.0" })
	void toCurrent_newerSchema_isRefusedAskingForANewerArchSim(String version) {
		JsonNode input = JSON.readTree("{\"schemaVersion\": \"%s\"}".formatted(version));
		assertThatThrownBy(() -> Upgrades.toCurrent(input)).isInstanceOf(UpgradeException.class)
			.hasMessage("this document uses schema %s, newer than the supported 1.8. Upgrade ArchSim to open it."
				.formatted(version));
	}

	@Test
	void parse_partOfAsManyDigitsAsPythonReads_isItsInteger() {
		assertThat(SchemaVersion.parse("0_".repeat(4_299) + "1.7")).isEqualByComparingTo(SchemaVersion.parse("1.7"));
	}

	/** Python's {@code int()} reads at most 4,300 digits, leading zeros counted. */
	@Test
	void parse_partOfMoreDigitsThanPythonReads_isNotAValidSchemaVersion() {
		String version = "1".repeat(4_301) + ".7";

		assertThatThrownBy(() -> SchemaVersion.parse(version)).isInstanceOf(UpgradeException.class)
			.hasMessage("'%s' is not a valid schemaVersion".formatted(version));
	}

	@Test
	void parse_partOfAHundredThousandUnderscoredGroups_isNotAValidSchemaVersion() {
		String version = "0_".repeat(100_000) + "1.7";

		assertThatThrownBy(() -> SchemaVersion.parse(version)).isInstanceOf(UpgradeException.class)
			.hasMessage("'%s' is not a valid schemaVersion".formatted(version));
	}

	@Test
	void compareTo_dottedVersions_ordersThemAsTuplesOfIntegers() {
		assertThat(SchemaVersion.parse("1.10")).isGreaterThan(SchemaVersion.parse("1.7"));
		assertThat(SchemaVersion.parse("1.7.0")).isGreaterThan(SchemaVersion.parse("1.7"));
		assertThat(SchemaVersion.parse("0.9")).isLessThan(SchemaVersion.parse("1.0"));
		assertThat(SchemaVersion.parse(" 1 . 07")).isEqualByComparingTo(SchemaVersion.parse("1.7"));
	}

}
