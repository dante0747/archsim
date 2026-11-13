package io.archsim.benchmarks.machine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cgroups;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeServices;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ProcessResult;

/**
 * pg-mixed's stack as a machine check sees it, played by a test on the laptop's topology:
 * each container's CPU rising a millisecond a second, the VM losing one jiffy in a
 * hundred to steal, the service counting 750 requests at 0.5 ms each, k6 writing its
 * summaries and each probe printing its units.
 */
final class FakeStack {

	/** The laptop's VM: four physical cores, two hyperthreads each. */
	static final String SIBLINGS = "0 0-1\n1 0-1\n2 2-3\n3 2-3\n4 4-5\n5 4-5\n6 6-7\n7 6-7\n";

	private final Path folder;

	private final FakeTicker ticker = new FakeTicker(1_000_000);

	private final Logs logs = new Logs();

	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

	private final AtomicInteger procStatReads = new AtomicInteger();

	private final FakeDocker docker = new FakeDocker(this::answer);

	private final String p95;

	/**
	 * The stack, k6's p95 at every rate 9 ms.
	 * @param folder the folder k6's summaries land in, beside the results
	 */
	FakeStack(Path folder) {
		this(folder, "9.0");
	}

	/**
	 * The stack with k6's p95 at every rate.
	 * @param folder the folder k6's summaries land in, beside the results
	 * @param p95 k6's p95
	 */
	FakeStack(Path folder, String p95) {
		this.folder = folder;
		this.p95 = p95;
	}

	/**
	 * What the checks run with: k6 given CPUs 0, 1, 3, 6 and 7, as runs up to 6 had it.
	 * @return the tools
	 */
	CheckTools tools() {
		Compose compose = Compose.of(this.docker, this.folder.resolve("compose.yaml"),
				Map.of("BENCH_CPUS_K6", "0,1,3,6,7"), this.logs.log());
		Instruments instruments = new Instruments(compose, new Cgroups(compose, this.folder, this.ticker),
				new FakeServices(this::service), this.ticker);
		return new CheckTools(instruments, Cpusets.declaredIn(RepositoryLayout.current().compose()),
				Map.of("BENCH_CPUS_K6", "0,1,3,6,7"), this.folder.resolve(".k6"),
				() -> JsonText.object("{\"cpu\": \"a laptop\"}"), this.logs.log());
	}

	FakeDocker docker() {
		return this.docker;
	}

	FakeTicker ticker() {
		return this.ticker;
	}

	Logs logs() {
		return this.logs;
	}

	List<String> requests() {
		return List.copyOf(this.requests);
	}

	/**
	 * Windows' counters once a second over three hours of the test's clock: 3,000 MHz,
	 * two logical processors each 20% busy.
	 * @return the samples
	 */
	HostSamples samples() {
		HostSamples samples = new HostSamples();
		long start = (long) (this.ticker.now() * 1000);
		for (long at = start; at < start + 3 * 3_600_000; at += 1000) {
			samples.add("{\"t\": " + at + ", \"processor information(_total)|actual frequency\": 3000.0, "
					+ "\"hyper-v hypervisor logical processor(hv lp 0)|% total run time\": 20.0, "
					+ "\"hyper-v hypervisor logical processor(hv lp 1)|% total run time\": 20.0}");
		}
		return samples;
	}

	// Docker: the topology, each container's id and counters, the VM's CPU, k6's run and
	// the probes
	private ProcessResult answer(String command) {
		if (command.endsWith("postgres:16-alpine -s")) {
			return FakeDocker.printing(SIBLINGS);
		}
		if (command.contains(" ps -q ") && !command.contains("--filter")) {
			return FakeDocker.printing("id-" + command.substring(command.lastIndexOf(' ') + 1));
		}
		if (command.endsWith("cat /proc/stat")) {
			int reads = this.procStatReads.incrementAndGet();
			return FakeDocker.printing("cpu  " + (reads * 99) + " 0 0 0 0 0 0 " + reads + " 0 0\n");
		}
		if (command.endsWith("cat /sys/fs/cgroup/cpu.stat")) {
			return FakeDocker.printing("usage_usec " + (long) (this.ticker.now() * 1000) + "\n");
		}
		if (command.startsWith("docker wait ")) {
			this.ticker.sleep(12);
		}
		if (command.contains(" k6 run ")) {
			write(command.substring(command.indexOf("OUT=") + "OUT=".length(), command.indexOf(" k6 run")));
			this.ticker.sleep(20);
		}
		return FakeDocker.printing(answerProbe(command));
	}

	// a probe's line when its logs are read, k6's container's id
	private static String answerProbe(String command) {
		if (command.startsWith("docker logs ")) {
			return "{\"units\": 20000, \"cpuMs\": 600.0, \"wallMs\": 12000.0, \"lateMs\": 3, \"sink\": 1}\n";
		}
		if (command.contains("--filter name=archsim-bench-k6-run")) {
			return "k6-run-id\n";
		}
		if (command.contains("console.log(Date.now())")) {
			return "1000000500\n";
		}
		return "";
	}

	private JsonNode service(String request) {
		this.requests.add(request);
		return JsonText.read("{\"requests\": 750, \"cpuMsPerRequest\": 0.5}");
	}

	// k6's summary of 15 s at 50 req/s, all succeeding
	private void write(String name) {
		try {
			Files.createDirectories(this.folder.resolve(".k6"));
			Files.writeString(this.folder.resolve(".k6").resolve(name + ".json"), """
					{"http_req_duration{scenario:measure,expected_response:true}":
					 {"values": {"med": 1.0, "p(95)": %s, "p(99)": 12.0, "avg": 2.0, "count": 750}},
					 "http_reqs{scenario:measure}": {"values": {"count": 750}}}""".formatted(this.p95));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
