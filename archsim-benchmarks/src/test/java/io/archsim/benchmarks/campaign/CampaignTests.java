package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.HarnessStopException;
import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cgroups;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeServices;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A campaign on Docker a test plays, its experiments run on pg-mixed's latest run played
 * back: planned before any stack starts, then each stack started, its experiments run and
 * the stack stopped, run after run.
 */
class CampaignTests {

	private static final String HELD_OUT = "held out";

	// the laptop's Docker VM, as its kernel lists the CPUs' siblings
	private static final String SIBLINGS = "0 0-1\n1 0-1\n2 2-3\n3 2-3\n4 4-5\n5 4-5\n6 6-7\n7 6-7\n";

	private final List<String> events = Collections.synchronizedList(new ArrayList<>());

	private final Logs logs = new Logs();

	@Test
	void plan_aStackOfASealedExperiment_stopsBeforeDocker(@TempDir Path root) throws IOException {
		Campaign campaign = campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS);

		Throwable thrown = catchThrowable(() -> campaign.plan(List.of("go-mixed"), null));

		assertThat(thrown).isInstanceOf(HarnessStopException.class).hasMessage(HELD_OUT);
		assertThat(this.events).isEmpty();
	}

	@Test
	void planAll_sealedExperiments_areLeftOut(@TempDir Path root) throws IOException {
		Map<String, List<String>> plan = campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).planAll(null);

		assertThat(plan).containsExactly(Map.entry("postgres", List.of("pg-mixed", "pg-hot-row")),
				Map.entry("cache", List.of("cache-aside")), Map.entry("kafka", List.of("kafka")));
	}

	@Test
	void planAll_sealedExperiments_saysWhy(@TempDir Path root) throws IOException {
		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).planAll(null);

		assertThat(this.logs.text().lines()).contains("[12:34:56] leaving out chain: held out");
	}

	@Test
	void plan_oneExperiment_isItAlone(@TempDir Path root) throws IOException {
		Map<String, List<String>> plan = campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS)
			.plan(List.of("postgres"), "pg-hot-row");

		assertThat(plan).containsExactly(Map.entry("postgres", List.of("pg-hot-row")));
	}

	// run 1 of every experiment, then run 2,
	// each stack's last run removed before it starts
	@Test
	void run_twoRuns_rotateThroughTheStacks(@TempDir Path root) throws IOException {
		Map<String, List<String>> plan = new LinkedHashMap<>();
		plan.put("postgres", List.of("pg-mixed", "pg-hot-row"));
		plan.put("cache", List.of("cache-aside"));

		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).run(plan, new Campaign.Options(false, 2, false));

		assertThat(stacksAndExperiments()).containsExactly("down postgres", "up postgres", "calibrate pg-mixed",
				"calibrate pg-hot-row", "down postgres", "down cache", "up cache", "calibrate cache-aside",
				"down cache", "down postgres", "up postgres", "calibrate pg-mixed", "calibrate pg-hot-row",
				"down postgres", "down cache", "up cache", "calibrate cache-aside", "down cache");
	}

	@Test
	void run_keep_leavesTheLastStackRunning(@TempDir Path root) throws IOException {
		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).run(Map.of("postgres", List.of("pg-hot-row")),
				new Campaign.Options(false, 1, true));

		assertThat(stacksAndExperiments()).containsExactly("down postgres", "up postgres", "calibrate pg-hot-row");
	}

	// kept after the last run only
	@Test
	void run_keepOverTwoRuns_stopsTheStackBetweenThem(@TempDir Path root) throws IOException {
		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).run(Map.of("postgres", List.of("pg-hot-row")),
				new Campaign.Options(false, 2, true));

		assertThat(stacksAndExperiments()).containsExactly("down postgres", "up postgres", "calibrate pg-hot-row",
				"down postgres", "down postgres", "up postgres", "calibrate pg-hot-row");
	}

	@Test
	void run_anExperimentThatFails_stillStopsItsStack(@TempDir Path root) throws IOException {
		Campaign campaign = campaign(root, SIBLINGS, new PlayedSteps.Script(false, 0, "pg-mixed"));

		Throwable thrown = catchThrowable(
				() -> campaign.run(Map.of("postgres", List.of("pg-mixed")), new Campaign.Options(false, 1, false)));

		assertThat(thrown).hasMessage("pg-mixed's calibration failed");
		assertThat(this.events).last().isEqualTo("down postgres");
	}

	@Test
	void run_archive_keepsThePreviousResultsAndSaysWhere(@TempDir Path root) throws IOException {
		Path previous = Files.createDirectories(root.resolve("benchmarks/results/pg-hot-row"));
		Files.writeString(previous.resolve("measured.json"), "{}");

		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).run(Map.of("postgres", List.of("pg-hot-row")),
				new Campaign.Options(true, 1, false));

		assertThat(this.logs.text().lines())
			.contains("[12:34:56] pg-hot-row: kept the previous results in benchmarks/results/pg-hot-row/run-1");
	}

	@Test
	void run_anExperiment_recordsItsImage(@TempDir Path root) throws IOException {
		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).run(Map.of("postgres", List.of("pg-hot-row")),
				new Campaign.Options(false, 1, false));

		assertThat(environment(root, "pg-hot-row").at("/images/service").stringValue()).startsWith("node:24-alpine");
	}

	@Test
	void run_anExperiment_recordsItsCpusets(@TempDir Path root) throws IOException {
		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).run(Map.of("postgres", List.of("pg-hot-row")),
				new Campaign.Options(false, 1, false));

		assertThat(environment(root, "pg-hot-row").get("cpusets"))
			.isEqualTo(JsonText.read("{\"api\": \"2\", \"postgres\": \"4,5\", \"k6\": \"0,1,6,7\"}"));
	}

	@Test
	void run_dockerListingTheCpusSiblings_recordsTheTopology(@TempDir Path root) throws IOException {
		campaign(root, SIBLINGS, PlayedSteps.Script.TOP_MEETS).run(Map.of("postgres", List.of("pg-hot-row")),
				new Campaign.Options(false, 1, false));

		assertThat(environment(root, "pg-hot-row").at("/topology/physicalCores"))
			.isEqualTo(JsonText.read("{\"api\": 1, \"postgres\": 1, \"k6\": 2}"));
	}

	@Test
	void run_dockerListingNoSiblings_recordsNoTopology(@TempDir Path root) throws IOException {
		campaign(root, "", PlayedSteps.Script.TOP_MEETS).run(Map.of("postgres", List.of("pg-hot-row")),
				new Campaign.Options(false, 1, false));

		assertThat(environment(root, "pg-hot-row").has("topology")).isFalse();
	}

	// the stacks started and stopped and the experiments calibrated, in order
	private List<String> stacksAndExperiments() {
		return this.events.stream()
			.filter((event) -> event.startsWith("up ") || event.startsWith("down ") || event.startsWith("calibrate "))
			.toList();
	}

	private static JsonNode environment(Path root, String experiment) {
		return ResultFiles.read(root.resolve("benchmarks/results").resolve(experiment).resolve("environment.json"))
			.orElseThrow();
	}

	// a campaign on Docker that lists the siblings it is given, the held-out experiments
	// sealed, each experiment's steps played back
	private Campaign campaign(Path root, String siblings, PlayedSteps.Script script) throws IOException {
		Path benchmarks = Files.createDirectories(root.resolve("benchmarks"));
		Files.copy(RepositoryLayout.current().compose(), benchmarks.resolve("compose.yaml"));
		RepositoryLayout layout = new RepositoryLayout(root);
		FakeDocker docker = new FakeDocker((command) -> FakeDocker.printing(answer(command, siblings)));
		FakeTicker ticker = new FakeTicker(0);
		Compose compose = Compose.of(docker, layout.compose(), Map.of(), this.logs.log());
		Instruments instruments = new Instruments(compose, new Cgroups(compose, root, ticker),
				new FakeServices((request) -> JsonText.read("{}")), ticker);
		return new Campaign(instruments,
				(running, serviceCpus) -> new PlayedSteps(layout.results(), script, this.events),
				CampaignTests::sealHeldOut, new CampaignMachine(layout, Map.of(), docker, this.logs.log()));
	}

	// a stack started or stopped kept as an event; the siblings listed when asked
	private String answer(String command, String siblings) {
		int profile = command.indexOf("--profile ");
		if (profile >= 0 && (command.contains(" up ") || command.contains(" down "))) {
			String stack = command.substring(profile + "--profile ".length()).split(" ", -1)[0];
			this.events.add((command.contains(" up ") ? "up " : "down ") + stack);
		}
		return command.contains(" --entrypoint sh ") ? siblings : "";
	}

	private static Optional<String> sealHeldOut(Experiment experiment) {
		return experiment.isHeldOut() ? Optional.of(HELD_OUT) : Optional.empty();
	}

}
