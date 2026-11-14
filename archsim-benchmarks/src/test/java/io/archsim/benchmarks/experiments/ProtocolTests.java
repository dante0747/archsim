package io.archsim.benchmarks.experiments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolTests {

	// the validation page's protocol states the window each step is measured in
	@Test
	void measurementWindow_theValidationPage_statesIt() throws IOException {
		String page = Files.readString(RepositoryLayout.current().validationPage(), StandardCharsets.UTF_8);

		assertThat(page).contains("4. **Measure** each step three times: " + Protocol.WARMUP_S + " s of warm-up, then "
				+ Protocol.DURATION_S + " s measured");
	}

}
