package io.archsim.app.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.TestProperties;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** The example designs, read from the examples folder. */
class ExampleServiceTests {

	@TempDir
	private Path folder;

	@Test
	void summaries_designs_listThemByFileNameWithTheirMetadata() throws IOException {
		write("b-design.json", "{\"metadata\": {\"name\": \"B\", \"tags\": [\"x\"]}, \"scenarios\": [{}, {}]}");
		write("a-design.json", "{\"metadata\": {\"description\": \"First.\"}}");

		List<ExampleSummary> summaries = service().summaries();

		assertThat(summaries).containsExactly(new ExampleSummary("a-design", "a-design", "First.", List.of(), 0),
				new ExampleSummary("b-design", "B", null, List.of("x"), 2));
	}

	@Test
	void summaries_fileThatIsNotJson_isSkipped() throws IOException {
		write("broken.json", "{not json");
		write("fine.json", "{}");

		assertThat(service().summaries()).extracting(ExampleSummary::id).containsExactly("fine");
	}

	@Test
	void design_unknownExample_isNotFound() {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service().design("nope"))
			.withMessage("Unknown example 'nope'")
			.extracting(ApiException::status)
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void design_copy_canBeChangedWithoutChangingTheExample() throws IOException {
		write("one.json", "{\"schemaVersion\": \"1.3\"}");
		ExampleService examples = service();

		examples.design("one").put("schemaVersion", "9.9");

		assertThat(examples.design("one").path("schemaVersion").asString()).isEqualTo("1.3");
	}

	private ExampleService service() {
		ArchsimProperties properties = TestProperties.with("examples-dir=" + this.folder);
		return new ExampleService(new ExampleRepository(properties, JsonMapper.builder().build()));
	}

	private void write(String name, String json) throws IOException {
		Files.writeString(this.folder.resolve(name), json);
	}

}
