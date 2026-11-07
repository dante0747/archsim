package io.archsim.engine.yaml;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * YAML from sources ArchSim does not control, read as PyYAML's safe loader reads it,
 * within the Python engine's bounds.
 */
class YamlDocumentsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void one_scalars_haveTheTypesPyYamlGivesThem() {
		JsonNode document = YamlDocuments.one("""
				a: yes
				b: on
				c: 0777
				d: 1:30
				e: 1e5
				f: 1.5e+3
				g: ~
				h: 0x1F
				k: 0b101
				m: 1_000
				n: -0o7
				o: 08
				""");

		assertThat(document).isEqualTo(JSON.readTree("""
				{"a": true, "b": true, "c": 511, "d": 90, "e": "1e5", "f": 1500.0, "g": null, "h": 31, "k": 5,
				 "m": 1000, "n": "-0o7", "o": "08"}"""));
	}

	@Test
	void one_timestamps_areTheirTextAsPythonWritesTheDateOrTime() {
		JsonNode document = YamlDocuments.one("""
				day: 2024-05-01
				local: 2002-12-14 01:02:03
				utc: 2002-12-14 01:02:03Z
				offset: 2001-12-14t21:59:43.10-05:00
				""");

		assertThat(document).isEqualTo(JSON.readTree("""
				{"day": "2024-05-01", "local": "2002-12-14 01:02:03", "utc": "2002-12-14 01:02:03+00:00",
				 "offset": "2001-12-14 21:59:43.100000-05:00"}"""));
	}

	@Test
	void one_timestampThatIsNoDate_isRefusedInPythonsWords() {
		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.one("a: 2024-02-30"))
			.withMessage("day is out of range for month");
	}

	@Test
	void one_mergeKey_mergesTheMappingBeforeItsOwnKeys() {
		JsonNode document = YamlDocuments.one("base: &b {x: 1, y: 2}\nsvc:\n  <<: *b\n  y: 3\n  z: 4\n");

		assertThat(document.path("svc")).isEqualTo(JSON.readTree("{\"x\": 1, \"y\": 3, \"z\": 4}"));
	}

	@Test
	void one_listOfMergeKeys_mergesTheLastMappingFirstAndKeepsEachKeysFirstPlace() {
		JsonNode document = YamlDocuments.one("""
				x: &x {a: 1, b: 1}
				y: &y {b: 2, c: 2}
				z:
				  <<: [*x, *y]
				  d: 3
				""");

		assertThat(document.path("z").propertyNames()).containsExactly("b", "c", "a", "d");
		assertThat(document.path("z")).isEqualTo(JSON.readTree("{\"a\": 1, \"b\": 1, \"c\": 2, \"d\": 3}"));
	}

	@Test
	void one_mergeKeyAfterOwnKeys_putsTheMergedKeysFirst() {
		JsonNode document = YamlDocuments.one("base: &b {x: 1}\nsvc:\n  y: 2\n  <<: *b\n");

		assertThat(document.path("svc").propertyNames()).containsExactly("x", "y");
	}

	@Test
	void one_keyWrittenTwice_keepsItsFirstPlaceAndItsLastValue() {
		JsonNode document = YamlDocuments.one("a: 1\nb: 2\na: 3\n");

		assertThat(document.propertyNames()).containsExactly("a", "b");
		assertThat(document.path("a").intValue()).isEqualTo(3);
	}

	@Test
	void one_explicitTags_areReadAsPyYamlReadsThem() {
		JsonNode document = YamlDocuments.one("""
				a: !!int "0x1F"
				b: !!float "1:30"
				c: !!bool "Off"
				d: !!null "x"
				e: !!str 12
				f: !!binary aGk=
				g: !!timestamp 2024-05-01
				""");

		assertThat(document).isEqualTo(JSON.readTree("""
				{"a": 31, "b": 90.0, "c": false, "d": null, "e": "12", "f": "aGk=", "g": "2024-05-01"}"""));
	}

	@Test
	void one_explicitIntThatIsNoNumber_isRefusedInPythonsWords() {
		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.one("a: !!int abc"))
			.withMessage("invalid literal for int() with base 10: 'abc'");
	}

	@Test
	void one_explicitFloatThatIsNoNumber_isRefusedInPythonsWords() {
		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.one("a: !!float abc"))
			.withMessage("could not convert string to float: 'abc'");
	}

	@Test
	void one_explicitEmptyInt_failsAsPythonIndexesIt() {
		assertThatExceptionOfType(YamlScalarException.class).isThrownBy(() -> YamlDocuments.one("a: !!int ''"))
			.withMessage("string index out of range");
	}

	@Test
	void one_explicitBoolThatIsNoBoolean_failsAsPythonLooksItUp() {
		assertThatExceptionOfType(YamlKeyException.class).isThrownBy(() -> YamlDocuments.one("a: !!bool Maybe"))
			.withMessage("'maybe'");
	}

	@Test
	void one_explicitTimestampThatIsNoTimestamp_failsAsPythonMatchesIt() {
		assertThatExceptionOfType(YamlScalarException.class).isThrownBy(() -> YamlDocuments.one("a: !!timestamp soon"))
			.withMessage("'NoneType' object has no attribute 'groupdict'");
	}

	@Test
	void one_explicitBinaryThatIsNoBase64_isNotValidYaml() {
		assertThatExceptionOfType(InvalidYamlException.class).isThrownBy(() -> YamlDocuments.one("a: !!binary é"))
			.withMessage("failed to convert base64 data into ascii");
	}

	@Test
	void one_explicitScalarTagOnASequence_isNotValidYaml() {
		assertThatExceptionOfType(InvalidYamlException.class).isThrownBy(() -> YamlDocuments.one("a: !!int [1]"))
			.withMessage("expected a scalar node, but found sequence");
	}

	@Test
	void one_explicitSequenceTagOnAMapping_isNotValidYaml() {
		assertThatExceptionOfType(InvalidYamlException.class).isThrownBy(() -> YamlDocuments.one("a: !!seq {b: 1}"))
			.withMessage("expected a sequence node, but found mapping");
	}

	@Test
	void one_valueWrittenAsEquals_isNotValidYaml() {
		assertThatExceptionOfType(InvalidYamlException.class).isThrownBy(() -> YamlDocuments.one("a: =\n"))
			.withMessageContaining("tag:yaml.org,2002:value");
	}

	@Test
	void one_keyWrittenAsEquals_staysText() {
		assertThat(YamlDocuments.one("=: a\n")).isEqualTo(JSON.readTree("{\"=\": \"a\"}"));
	}

	@Test
	void one_keysThatAreNotText_areTheirTextAsPythonWritesIt() {
		JsonNode document = YamlDocuments.one("2: a\nyes: b\n~: c\n1.5: d\n");

		assertThat(document)
			.isEqualTo(JSON.readTree("{\"2\": \"a\", \"True\": \"b\", \"None\": \"c\", \"1.5\": \"d\"}"));
	}

	@Test
	void one_emptyText_isNull() {
		assertThat(YamlDocuments.one("").isNull()).isTrue();
	}

	@Test
	void one_severalDocuments_isNotValidYaml() {
		assertThatExceptionOfType(InvalidYamlException.class).isThrownBy(() -> YamlDocuments.one("a: 1\n---\nb: 2\n"))
			.withMessage("expected one YAML document, found several");
	}

	@Test
	void one_textThatIsNotYaml_isNotValidYamlInTheParsersWords() {
		assertThatExceptionOfType(InvalidYamlException.class)
			.isThrownBy(() -> YamlDocuments.one("services: [unclosed\n"))
			.withMessageStartingWith("while parsing a flow sequence")
			.withMessageContaining("expected ',' or ']'");
	}

	@Test
	void one_pythonTag_isNotValidYaml() {
		assertThatExceptionOfType(InvalidYamlException.class)
			.isThrownBy(() -> YamlDocuments.one("a: !!python/object/apply:os.system [echo]\n"))
			.withMessageContaining("tag:yaml.org,2002:python/object/apply:os.system");
	}

	@Test
	void all_emptyDocuments_areNull() {
		assertThat(YamlDocuments.all("---\n---\na: 1\n")).containsExactly(JSON.nullNode(), JSON.readTree("{\"a\": 1}"));
	}

	@Test
	void all_aliasesThatExpandPastTheLimit_areRefused() {
		String bomb = """
				a: &a [x, x, x, x, x, x, x, x, x, x]
				b: &b [*a, *a, *a, *a, *a, *a, *a, *a, *a, *a]
				c: &c [*b, *b, *b, *b, *b, *b, *b, *b, *b, *b]
				d: &d [*c, *c, *c, *c, *c, *c, *c, *c, *c, *c]
				e: &e [*d, *d, *d, *d, *d, *d, *d, *d, *d, *d]
				f: [*e, *e, *e, *e, *e, *e, *e, *e, *e, *e]
				""";

		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.all(bomb))
			.withMessage("the YAML expands to more than 200,000 values through aliases");
	}

	@Test
	void all_recursiveAlias_isRefused() {
		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.all("a: &a [*a]\n"))
			.withMessage("the YAML contains a recursive alias");
	}

	@Test
	void all_nestingPastAHundredLevels_isRefused() {
		String deep = "[".repeat(102) + "]".repeat(102);

		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.all(deep))
			.withMessage("the YAML nests deeper than 100 levels");
	}

	@Test
	void all_nestingPastWhatTheParserAllows_isRefusedInTheBoundsWords() {
		String deep = "[".repeat(1001) + "]".repeat(1001);

		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.all(deep))
			.withMessage("the YAML nests deeper than 100 levels");
	}

	@Test
	void all_nestingOfAHundredLevels_isRead() {
		String deep = "[".repeat(101) + "]".repeat(101);

		assertThat(YamlDocuments.all(deep)).hasSize(1);
	}

	@Test
	void all_textOverFiveMegabytes_isRefused() {
		String large = "a: " + "x".repeat(5 * 1024 * 1024) + "\n";

		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> YamlDocuments.all(large))
			.withMessage("the YAML is larger than 5 MB");
	}

}
