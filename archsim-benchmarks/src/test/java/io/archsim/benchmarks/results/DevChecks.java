package io.archsim.benchmarks.results;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Development checks for the tests, as {@code resimulate} writes them: a calibration's
 * derived service time and the knee an engine found.
 */
public final class DevChecks {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private DevChecks() {
	}

	/**
	 * Writes an engine's check of a calibration.
	 * @param dev {@code benchmarks/results/dev/}
	 * @param engine the engine's folder
	 * @param check the calibration and what the engine found
	 * @throws IOException when the file cannot be written
	 */
	public static void write(Path dev, String engine, Check check) throws IOException {
		Path folder = dev.resolve(engine).resolve(check.experiment());
		Files.createDirectories(folder);
		ObjectNode json = NODES.objectNode();
		json.putObject("inputs").put("serviceTimeMs", check.serviceTimeMs());
		json.putObject("comparison").putObject("predictedKnee").put("kneeRps", check.knee());
		Files.writeString(folder.resolve(check.calibration() + ".json"), json.toString(), StandardCharsets.UTF_8);
	}

	/**
	 * A calibration an engine checked.
	 *
	 * @param experiment its experiment
	 * @param calibration its file name, the calibration's time
	 * @param knee the knee the engine found, or {@code null}
	 * @param serviceTimeMs the service time it derived
	 */
	public record Check(String experiment, String calibration, @Nullable Double knee, double serviceTimeMs) {

		/**
		 * A pg-mixed calibration whose service time is 2 ms.
		 * @param calibration its file name
		 * @param knee the knee the engine found, or {@code null}
		 * @return the check
		 */
		public static Check of(String calibration, @Nullable Double knee) {
			return new Check("pg-mixed", calibration, knee, 2.0);
		}

	}

}
