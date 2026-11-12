package io.archsim.engine.generated;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed files the engine generates are the engine's output today
 * ({@link GeneratedFile}): a change to the catalog or the analysis, or an edit by hand,
 * shows up here until {@code ./gradlew :archsim-engine:generate} writes them again.
 */
class GeneratedFileTests {

	@ParameterizedTest
	@EnumSource(GeneratedFile.class)
	void committedFile_generatedByTheEngine_isItsOutputToday(GeneratedFile file) throws IOException {
		String committed = Files.readString(file.target());

		assertThat(committed).as("%s: run ./gradlew :archsim-engine:generate", file.target()).isEqualTo(file.text());
	}

}
