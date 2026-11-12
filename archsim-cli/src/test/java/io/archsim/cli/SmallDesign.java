package io.archsim.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The smallest design the CLI's tests run: clients calling an external API that answers
 * in 20 ms, with the workload a test gives.
 */
final class SmallDesign {

	/** A short, steady workload: 100 requests a second for 6 seconds, seeded. */
	static final String WORKLOAD = """
			{"seed": 7, "warmupS": 1, "durationS": 6, "drainS": 600, "rps": 100, "arrivalProcess": "deterministic"}""";

	private SmallDesign() {
	}

	/**
	 * Writes the design with this workload to {@code design.json} in a folder.
	 * @param folder where the file goes
	 * @param workload the workload, as JSON
	 * @return the file
	 * @throws IOException when the file cannot be written
	 */
	static Path write(Path folder, String workload) throws IOException {
		Path design = folder.resolve("design.json");
		Files.writeString(design, """
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				             {"id": "api", "type": "external_api",
				              "config": {"latencyMs": {"kind": "constant", "value": 20}}}],
				   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": %s}""".formatted(workload));
		return design;
	}

}
