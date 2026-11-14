package io.archsim.tools.action;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archsim-check action's check step
 * ({@code .github/actions/archsim-check/action.yml}), which builds ArchSim's image and
 * runs the check in it (ADR-0060), run by bash with a stand-in for {@code docker}
 * ({@link CheckStep}). Building the image and running the CLI for real is CI's
 * {@code archsim-check} job's. The step runs in bash on a Linux runner, and so do these
 * tests.
 */
@EnabledOnOs(OS.LINUX)
class ArchsimCheckActionTests {

	private static final String CHECKOUT = CheckStep.ACTION + "/../../..";

	@Test
	void build_theImage_isBuiltFromTheActionsOwnCheckout(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of());

		assertThat(step.docker().getFirst()).containsExactly("build", "--file",
				CHECKOUT + "/.github/actions/archsim-check/Dockerfile", "--tag", step.tag(), CHECKOUT);
	}

	// two jobs on one runner's Docker may build the same image: each gets its own tag
	@Test
	void build_twoChecks_eachTagTheirImageWithANameOfTheirOwn(@TempDir Path folder) throws Exception {
		CheckStep first = CheckStep.run(folder.resolve("first"), Map.of());
		CheckStep second = CheckStep.run(folder.resolve("second"), Map.of());

		assertThat(first.tag()).matches("archsim-check:[0-9a-f-]{36}").isNotEqualTo(second.tag());
	}

	@Test
	void run_theCheck_runsInTheImageByItsTag(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of());

		assertThat(step.check().getFirst()).isEqualTo("check");
	}

	// a self-hosted runner keeps no image per job, and another job's tag keeps the image
	// it
	// shares with this one; BuildKit's cache stays
	@Test
	void imageRm_aCheckThatHasRun_removesOnlyItsOwnTag(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of());

		assertThat(step.docker().get(2)).containsExactly("image", "rm", step.tag());
	}

	@Test
	void build_anotherArchsimCheckout_isTheOneBuilt(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("ARCHSIM_DIR", "tools/archsim"));

		String checkout = step.workspace() + "/tools/archsim";
		assertThat(List.of(step.docker().getFirst().get(2), step.docker().getFirst().getLast()))
			.containsExactly(checkout + "/.github/actions/archsim-check/Dockerfile", checkout);
	}

	@Test
	void check_aCheckThatPasses_runsTheCliWithTheInputs(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("FAIL_ON", "objectives, regression"));

		assertThat(List.of(step.exitCode(), step.outputs().get("exit-code"), step.outputs().get("passed")))
			.containsExactly(0, "0", "true");
		assertThat(step.check()).containsExactly("check", step.workspace() + "/archsim/design.json", "--format",
				"markdown", "--out", step.outputs().get("markdown"), "--json-out", step.outputs().get("json"),
				"--report-url", "https://github.com/o/r/actions/runs/7", "--max-p95-increase", "0.10",
				"--max-cost-increase", "0.10", "--fail-on", "objectives", "--fail-on", "regression");
	}

	// the workspace to read, and the action's own folder to write in
	@Test
	void run_theContainer_hasNoNetworkTheRunnersUserAndOnlyItsFiles(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of());

		List<String> run = step.docker().get(1);
		String files = step.files();
		String workspace = step.workspace().toString();
		assertThat(run.subList(0, run.indexOf(step.tag()))).containsExactly("run", "--rm", "--network", "none",
				"--user", id("-u") + ":" + id("-g"), "--env", "HOME=/tmp", "--volume",
				workspace + ":" + workspace + ":ro", "--volume", files + ":" + files, "--workdir", workspace);
	}

	@Test
	void outputs_theCommentAndResult_areWrittenInTheActionsOwnFolder(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of());

		assertThat(List.of(step.outputs().get("markdown"), step.outputs().get("json"))).containsExactly(
				step.files() + "/archsim-check-archsim_design.json.md",
				step.files() + "/archsim-check-archsim_design.json.json");
	}

	@Test
	void check_aScenarioABaselineAndTheKnee_arePassedOn(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder,
				Map.of("SCENARIO", "busy", "BASELINE_REF", "origin/main", "KNEE", "true"));

		List<String> check = step.check();
		assertThat(check.subList(check.indexOf("--scenario"), check.indexOf("--knee") + 1))
			.containsExactly("--scenario", "busy", "--baseline-ref", "origin/main", "--knee");
	}

	@Test
	void outputs_aScenario_hasItsOwnCommentAndFiles(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("SCENARIO", "busy"));

		assertThat(List.of(step.outputs().get("key"), step.outputs().get("slug")))
			.containsExactly("archsim/design.json#busy", "archsim_design.json_busy");
	}

	@Test
	void step_aCheckThatFails_leavesTheClisCommentAndExitCode(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("CHECK_EXIT", "1"));

		assertThat(List.of(step.exitCode(), step.outputs().get("exit-code"), step.outputs().get("passed")))
			.containsExactly(0, "1", "false");
		assertThat(step.summary()).isEqualTo("<!-- archsim-check -->\nthe verdict\n");
	}

	@Test
	void step_aCheckThatCannotRun_replacesTheComment(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("CHECK_EXIT", "2"));

		assertThat(List.of(step.outputs().get("exit-code"), step.outputs().get("passed"))).containsExactly("2",
				"false");
		List<String> comment = Files.readAllLines(Path.of(step.outputs().get("markdown")), StandardCharsets.UTF_8);
		assertThat(comment.subList(1, comment.size())).containsExactly(
				"### \u26a0\ufe0f ArchSim check could not run (exit 2)", "",
				"The design or its baseline could not be used; the job log says why.");
	}

	// 125: Docker's own failure, which is not one of the CLI's codes
	@Test
	void step_aContainerDockerCannotRun_isACheckThatCannotRun(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("CHECK_EXIT", "125"));

		assertThat(List.of(step.outputs().get("exit-code"), step.outputs().get("passed"))).containsExactly("2",
				"false");
		assertThat(step.log()).contains("::error::The check's container ended with exit code 125");
		assertThat(step.summary().lines().toList().getLast())
			.isEqualTo("The check's container did not run to its end; the job log says why.");
	}

	@Test
	void step_anImageThatDoesNotBuild_isACheckThatCannotRun(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("BUILD_EXIT", "1"));

		assertThat(step.outputs().get("exit-code")).isEqualTo("2");
		assertThat(step.docker()).extracting(List::getFirst).containsExactly("build");
		assertThat(step.log()).contains("::error::ArchSim's image did not build");
		assertThat(step.summary().lines().toList().getLast())
			.isEqualTo("ArchSim's image did not build; the job log says why.");
	}

	@Test
	void step_aRunnerThatIsNotLinux_isACheckThatCannotRun(@TempDir Path folder) throws Exception {
		CheckStep step = CheckStep.run(folder, Map.of("RUNNER_OS", "macOS"));

		assertThat(step.outputs().get("exit-code")).isEqualTo("2");
		assertThat(step.docker()).isEmpty();
		assertThat(step.log()).contains("::error::The ArchSim check runs in a Linux container");
		assertThat(step.summary().lines().toList().getLast())
			.isEqualTo("The check runs in a Linux container: this runner is not Linux.");
	}

	// the runner's user or group id, as the step asks id for it
	private static String id(String which) throws IOException, InterruptedException {
		Process id = new ProcessBuilder("id", which).start();
		String value = new String(id.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		id.waitFor();
		return value;
	}

}
