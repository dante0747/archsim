package io.archsim.apitests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The APIs {@code archsim.api.start} may name, and the commands that start them. */
class StartableApiTests {

	@Test
	void of_nameInAnyCase_isThatApi() {
		assertThat(StartableApi.of(" Java ")).isEqualTo(StartableApi.JAVA);
	}

	@Test
	void of_removedPythonApi_failsListingTheChoices() {
		assertThatIllegalArgumentException().isThrownBy(() -> StartableApi.of("python"))
			.withMessage("archsim.api.start=python: the suite can start [java]");
	}

	/** The README's rule: 512 MB a run the worker takes at once, and 256 MB besides. */
	@ParameterizedTest
	@CsvSource({ "1, -Xmx768m", "3, -Xmx1792m" })
	void workerCommand_runsAtOnce_hasTheirHeapAndExitsOnRunningOutBeforeTheJar(int runs, String heap,
			@TempDir Path repo) throws IOException {
		Path jar = repo.resolve("archsim-app/build/libs/archsim-app.jar");
		Files.createDirectories(jar.getParent());
		Files.createFile(jar);
		Files.createDirectories(repo.resolve("examples"));
		Properties properties = new Properties();
		properties.setProperty("archsim.repoRoot", repo.toString());

		List<String> command = StartableApi.workerCommand(SuiteConfig.from(properties), runs);

		int jarAt = command.indexOf("-jar");
		assertThat(command.subList(1, jarAt)).containsExactly(heap, "-XX:+ExitOnOutOfMemoryError");
		assertThat(command.subList(jarAt, command.size())).containsExactly("-jar", jar.toString(), "worker");
	}

}
