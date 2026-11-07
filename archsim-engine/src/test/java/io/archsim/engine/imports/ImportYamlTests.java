package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * YAML read for an importer, what is wrong with it in the importers' words.
 */
class ImportYamlTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void one_document_isItsValue() {
		assertThat(ImportYaml.one("services: {}\n")).isEqualTo(JSON.readTree("{\"services\": {}}"));
	}

	@Test
	void all_documents_areTheirValues() {
		assertThat(ImportYaml.all("a: 1\n---\nb: 2\n")).containsExactly(JSON.readTree("{\"a\": 1}"),
				JSON.readTree("{\"b\": 2}"));
	}

	@Test
	void one_textThatIsNotYaml_isRefusedAsNotValidYamlInTheParsersWords() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> ImportYaml.one("services: [unclosed\n"))
			.withMessageStartingWith("not valid YAML: while parsing a flow sequence");
	}

	@Test
	void one_severalDocuments_isRefusedAsNotValidYaml() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> ImportYaml.one("a: 1\n---\nb: 2\n"))
			.withMessage("not valid YAML: expected one YAML document, found several");
	}

	@Test
	void all_valuePythonRefuses_isRefusedInPythonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> ImportYaml.all("a: &a [*a]\n"))
			.withMessage("the YAML contains a recursive alias");
	}

	@Test
	void one_keyPythonLooksUpInVain_isTextThatIsNotTheFormatMissingTheKey() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> ImportYaml.one("a: !!bool Maybe"))
			.withMessage("missing 'maybe'");
	}

	@Test
	void one_otherScalarPythonFailsOn_isTextThatIsNotTheFormatInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> ImportYaml.one("a: !!int ''"))
			.withMessage("string index out of range");
	}

}
