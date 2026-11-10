package io.archsim.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the recorded runs of the Python CLI cannot show: the limits {@code archsim run}
 * takes that the Python {@code run} did not (ADR-0051), a seed for a workload that is not
 * an object, an option named by the start of its name (argparse took it; picocli does
 * not), a report that cannot be written, and {@code archsim} without a command.
 */
class RunOptionsTests {

	private static final String WORKLOAD = """
			{"seed": 7, "warmupS": 1, "durationS": 6, "drainS": 600, "rps": 100, "arrivalProcess": "deterministic"}""";

	@TempDir
	private Path folder;

	@Test
	void run_moreRequestsThanAllowed_isRefusedInTheEnginesWords() throws IOException {
		Ran ran = run(WORKLOAD, "--max-requests", "100");

		assertThat(ran).isEqualTo(new Ran(1, "Cannot simulate: This run would generate about 600 requests, above the "
				+ "limit of 100. Reduce RPS or duration."));
	}

	@Test
	void run_hardlyAnyTimeAllowed_isStoppedAtItsFirstCheck() throws IOException {
		// the first check comes at the end of a quiet 3 s chunk, whatever the clock says
		Ran ran = run(WORKLOAD, "--max-wall-seconds", "0.000001");

		assertThat(ran).isEqualTo(new Ran(1, "Cannot simulate: Simulation exceeded the wall-clock limit of 0 s at "
				+ "simulated time 3.0 s. Reduce RPS, duration, fan-out or model size."));
	}

	@Test
	void run_aRequestLimitNotAboveZero_isAUsageError() throws IOException {
		Ran ran = run(WORKLOAD, "--max-requests", "-1");

		assertThat(ran).isEqualTo(new Ran(2, "--max-requests must be above 0"));
	}

	@Test
	void run_aWallClockLimitThatIsNotANumber_isAUsageError() throws IOException {
		Ran ran = run(WORKLOAD, "--max-wall-seconds", "NaN");

		assertThat(ran).isEqualTo(new Ran(2, "--max-wall-seconds must be a number above 0"));
	}

	@Test
	void run_anEndlessWallClockLimit_isAUsageError() throws IOException {
		Ran ran = run(WORKLOAD, "--max-wall-seconds", "Infinity");

		assertThat(ran).isEqualTo(new Ran(2, "--max-wall-seconds must be a number above 0"));
	}

	@Test
	void run_seedForAWorkloadThatIsNotAnObject_refusesTheDesign() throws IOException {
		Ran ran = run("null", "--seed", "5");

		assertThat(ran).isEqualTo(new Ran(1, "Cannot simulate: document is invalid"));
	}

	@Test
	void run_anOptionNamedByTheStartOfItsName_isAUsageError() throws IOException {
		Ran ran = run(WORKLOAD, "--max-req", "100");

		assertThat(ran.exitCode()).isEqualTo(2);
	}

	@Test
	void run_aReportToAFolderThatIsNotThere_isRefusedInOneLine() throws IOException {
		Path report = this.folder.resolve("nowhere").resolve("report.json");

		Ran ran = run(WORKLOAD, "--out", report.toString());

		assertThat(ran).isEqualTo(new Ran(1, "Cannot write %s: there is no such file or folder".formatted(report)));
	}

	@Test
	void archsim_withoutACommand_isAUsageError() {
		StringWriter err = new StringWriter();

		int exitCode = Archsim.commandLine()
			.setOut(new PrintWriter(new StringWriter()))
			.setErr(new PrintWriter(err))
			.execute();

		assertThat(new Ran(exitCode, err.toString().lines().findFirst().orElse("")))
			.isEqualTo(new Ran(2, "Missing required subcommand"));
	}

	// the exit code and the first line of standard error, for clients calling an API with
	// this workload
	private Ran run(String workload, String option, String value) throws IOException {
		Path design = this.folder.resolve("design.json");
		Files.writeString(design, """
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				             {"id": "api", "type": "external_api",
				              "config": {"latencyMs": {"kind": "constant", "value": 20}}}],
				   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": %s}""".formatted(workload));
		StringWriter err = new StringWriter();
		String[] args = { "run", design.toString(), option, value };
		int exitCode = Archsim.commandLine()
			.setOut(new PrintWriter(new StringWriter()))
			.setErr(new PrintWriter(err))
			.execute(args);
		return new Ran(exitCode, err.toString().lines().findFirst().orElse(""));
	}

	/**
	 * What a run of the command did.
	 *
	 * @param exitCode its exit code
	 * @param error the first line it printed on standard error
	 */
	private record Ran(int exitCode, String error) {

	}

}
