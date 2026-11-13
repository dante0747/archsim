package io.archsim.benchmarks.docker;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a stack's cpusets fall on physical cores: hyperthread siblings slow each other
 * down, so the runner gives no container under test a core it shares.
 */
class CpuTopologyTests {

	// the laptop's Docker VM: four physical cores, two hyperthreads each
	private static final Map<Integer, List<Integer>> LAPTOP = IntStream.range(0, 8)
		.boxed()
		.collect(Collectors.toMap((cpu) -> cpu, (cpu) -> List.of(cpu - cpu % 2, cpu - cpu % 2 + 1)));

	private static final Path COMPOSE = RepositoryLayout.current().compose();

	// the layout of runs up to 6: k6's five CPUs span three cores, one of them the
	// service's
	private static final Map<String, String> RUNS_UP_TO_6 = Map.of("api", "2", "postgres", "4,5", "k6", "0,1,3,6,7");

	@Test
	void of_runsUpTo6_countsEachContainersPhysicalCores() {
		ObjectNode topology = CpuTopology.of(ordered(RUNS_UP_TO_6), LAPTOP);

		// PostgreSQL's two CPUs are one core
		assertThat(topology.get("physicalCores")).isEqualTo(JsonText.read("{\"api\": 1, \"postgres\": 1, \"k6\": 3}"));
	}

	@Test
	void of_runsUpTo6_findsK6OnTheServicesCore() {
		ObjectNode topology = CpuTopology.of(ordered(RUNS_UP_TO_6), LAPTOP);

		assertThat(topology.get("sharedCores"))
			.isEqualTo(JsonText.read("[{\"containers\": [\"api\", \"k6\"], \"cores\": [[2, 3]]}]"));
	}

	@Test
	void of_theSiblings_listsEachCpusCore() {
		ObjectNode topology = CpuTopology.of(ordered(Map.of("api", "2")), LAPTOP);

		assertThat(topology.at("/siblings/3")).isEqualTo(JsonText.read("[2, 3]"));
	}

	// the runner's layouts leave the service's sibling idle
	@ParameterizedTest
	@ValueSource(strings = { "postgres", "cache" })
	void of_theRunnersLayout_sharesNoCoreWithTheService(String stack) {
		ObjectNode topology = CpuTopology.of(Cpusets.declaredIn(COMPOSE).of(stack, Map.of()), LAPTOP);

		assertThat(topology.get("sharedCores").valueStream().map((shared) -> shared.get("containers").toString()))
			.noneMatch((containers) -> containers.contains("\"api\""));
	}

	@Test
	void of_kafkasLayout_putsTheProducerAndTheConsumerOnOneCore() {
		ObjectNode topology = CpuTopology.of(Cpusets.declaredIn(COMPOSE).of("kafka", Map.of()), LAPTOP);

		assertThat(topology.get("sharedCores"))
			.contains(JsonText.read("{\"containers\": [\"producer\", \"consumer\"], \"cores\": [[2, 3]]}"));
	}

	@Test
	void of_containersOnCoresOfTheirOwn_sharesNone() {
		ObjectNode topology = CpuTopology.of(ordered(Map.of("api", "2", "k6", "0,1,6,7")), LAPTOP);

		assertThat(topology.get("sharedCores")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl" })
	void of_aHeldOutStack_sharesNoPhysicalCore(String experiment) {
		Experiment heldOut = Experiments.standard().get(experiment);

		ObjectNode topology = CpuTopology.of(Cpusets.declaredIn(COMPOSE).of(heldOut.stack(), Map.of()), LAPTOP);

		assertThat(topology.get("sharedCores")).isEmpty();
	}

	// no topology known: each CPU its own core
	@Test
	void coreOf_aCpuTheSiblingsDoNotName_isItself() {
		assertThat(CpuTopology.coreOf(5, Map.of())).containsExactly(5);
	}

	@Test
	void siblings_whatTheKernelListed_isEachCpusCore() {
		Map<Integer, List<Integer>> siblings = CpuTopology.siblings("0 0-1\n1 0-1\n2 2,3\n3 2,3\n").orElseThrow();

		assertThat(siblings).containsExactly(Map.entry(0, List.of(0, 1)), Map.entry(1, List.of(0, 1)),
				Map.entry(2, List.of(2, 3)), Map.entry(3, List.of(2, 3)));
	}

	// the script's quotes reach the container's shell as they are
	@Test
	void siblings_aMachine_listsThemThroughTheShellsInput() {
		FakeDocker docker = FakeDocker.silent();

		CpuTopology.siblings(Compose.of(docker, COMPOSE, Map.of(), new Logs().log()));

		assertThat(docker.commands()).containsExactly("docker run --rm -i --entrypoint sh postgres:16-alpine -s");
		assertThat(docker.inputs()).containsExactly(CpuTopology.SIBLINGS_SCRIPT);
	}

	@Test
	void siblings_nothingListed_isUnknown() {
		assertThat(CpuTopology.siblings("sh: no such file\n")).isEmpty();
	}

	// the containers in the order the runner names them: the stack's, then k6
	private static Map<String, String> ordered(Map<String, String> sets) {
		Map<String, String> ordered = new LinkedHashMap<>();
		for (String name : List.of("api", "postgres", "k6")) {
			if (sets.containsKey(name)) {
				ordered.put(name, sets.get(name));
			}
		}
		return ordered;
	}

}
