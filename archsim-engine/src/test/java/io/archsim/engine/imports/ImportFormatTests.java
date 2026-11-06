package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The formats a design is imported from, by name.
 */
class ImportFormatTests {

	@Test
	void named_nameOfAFormat_isTheFormat() {
		assertThat(ImportFormat.named("k8s")).isEqualTo(ImportFormat.K8S);
	}

	@Test
	void named_nameOfNoFormat_isRefusedInThePythonEnginesWords() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> ImportFormat.named("helm"))
			.withMessage("unknown import format 'helm' (compose, k8s, terraform)");
	}

}
