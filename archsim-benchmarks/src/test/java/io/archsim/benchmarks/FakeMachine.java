package io.archsim.benchmarks;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeServices;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.results.ResultFiles;

/**
 * The machine a command test plays: the harness's environment, Docker and the stacks'
 * services, in a repository with the stacks' compose file.
 *
 * @param environment the variables the harness started with
 * @param docker Docker
 * @param services the stacks' services
 */
record FakeMachine(Map<String, String> environment, FakeDocker docker, FakeServices services) {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	/**
	 * A machine on which pg-mixed's smoke test finds that the service counted so many of
	 * the 200 requests k6 sent.
	 * @param root the repository
	 * @param environment the variables the harness started with
	 * @param counted the requests the service counted
	 * @return the machine
	 * @throws IOException when the compose file cannot be copied
	 */
	static FakeMachine smoking(Path root, Map<String, String> environment, int counted) throws IOException {
		Path benchmarks = Files.createDirectories(root.resolve("benchmarks"));
		Files.copy(RepositoryLayout.current().compose(), benchmarks.resolve("compose.yaml"));
		Path summaries = benchmarks.resolve("results/.k6");
		FakeDocker docker = new FakeDocker((command) -> {
			if (command.contains(" k6 run ")) {
				ResultFiles.write(summaries.resolve("smoke.json"), JsonText.read("""
						{"http_req_duration{scenario:measure,expected_response:true}": {"values": {"count": 200}},
						 "http_reqs{scenario:measure}": {"values": {"count": 200}}}"""));
			}
			return FakeDocker.printing("");
		});
		return new FakeMachine(environment, docker, new FakeServices((request) -> stats(request, counted)));
	}

	/**
	 * The harness's command line on this machine, its log stamped at noon.
	 * @param root the repository
	 * @param out where the log and the commands' output go
	 * @param err where the commands' errors go
	 * @return the command line
	 */
	CommandLine commandLine(Path root, StringWriter out, StringWriter err) {
		PrintWriter writer = new PrintWriter(out, true);
		HarnessSetup setup = new HarnessSetup(new RepositoryLayout(root), new HarnessLog(writer, NOON),
				root.resolve("range-fit-cache.json"), this.environment, this.docker, this.services, new FakeTicker(0));
		return Benchmarks.commandLine(setup).setOut(writer).setErr(new PrintWriter(err, true));
	}

	// pg-mixed's service after a smoke test, counting so many requests
	private static JsonNode stats(String request, int counted) {
		if (!request.endsWith("/stats")) {
			return JsonText.read("{}");
		}
		return JsonText.read("""
				{"requests": %d, "cpuMs": 12.5, "counters": {}, "gc": {"collections": 1},
				 "timings": {"handler": {"count": %d}, "db_read": {"count": 181}, "db_write": {"count": 19}}}"""
			.formatted(counted, counted));
	}

}
