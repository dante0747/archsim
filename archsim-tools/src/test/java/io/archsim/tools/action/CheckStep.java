package io.archsim.tools.action;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.yaml.snakeyaml.Yaml;

/**
 * What the archsim-check action's check step did when bash ran it as a Linux runner runs
 * it, with {@code docker} replaced by a script that records each call and answers as the
 * case asks: what it asked Docker for, and the exit code, outputs and comment it left.
 *
 * @param exitCode the step's exit code
 * @param outputs the step's outputs, as GitHub reads its output file
 * @param docker each call to {@code docker}, its arguments apart
 * @param summary the step summary it wrote
 * @param log what it printed
 * @param workspace the runner's workspace
 * @param temp the runner's temporary folder
 */
record CheckStep(int exitCode, Map<String, String> outputs, List<List<String>> docker, String summary, String log,
		Path workspace, Path temp) {

	/** The repository, as the build gives it to the tests. */
	static final Path ROOT = Path.of(System.getProperty("archsim.repoRoot", "..")).toAbsolutePath().normalize();

	/** The action's folder. */
	static final Path ACTION = ROOT.resolve(".github").resolve("actions").resolve("archsim-check");

	/** Between the arguments of a call the stand-in records, which no argument holds. */
	private static final String SEPARATOR = "\u001f";

	private static final int TIMEOUT_SECONDS = 60;

	/**
	 * A stand-in for docker: it records each call, its arguments apart, and answers as
	 * the case asks.
	 */
	private static final String DOCKER = """
			#!/usr/bin/env bash
			(IFS=$'\\x1f'; printf '%s\\n' "$*") >> "$DOCKER_CALLS"
			case "$1" in
			  build)
			    [ "${BUILD_EXIT:-0}" = 0 ] || exit "$BUILD_EXIT" ;;
			  run)
			    # the CLI writes its comment to --out
			    while [ $# -gt 0 ]; do
			      [ "$1" = --out ] && printf '%s\\n' '<!-- archsim-check -->' 'the verdict' > "$2"
			      shift
			    done
			    exit "${CHECK_EXIT:-0}" ;;
			  image) ;;
			esac
			""";

	/**
	 * Runs the check step with the inputs and answers a case names, as GitHub would.
	 * @param folder a folder of the case's own
	 * @param answers the inputs and the stand-in's answers that differ from the defaults
	 * @return what the step did
	 * @throws IOException when the step's files cannot be written or read
	 * @throws InterruptedException when interrupted while it runs
	 */
	static CheckStep run(Path folder, Map<String, String> answers) throws IOException, InterruptedException {
		Path workspace = Files.createDirectories(folder.resolve("work"));
		Path temp = Files.createDirectories(folder.resolve("temp"));
		Path bin = Files.createDirectories(folder.resolve("bin"));
		Path docker = Files.writeString(bin.resolve("docker"), DOCKER, StandardCharsets.UTF_8);
		docker.toFile().setExecutable(true);
		for (String name : List.of("output", "summary", "docker-calls")) {
			Files.writeString(temp.resolve(name), "", StandardCharsets.UTF_8);
		}
		// from a file, as GitHub runs a step: bash ... -eo pipefail {0}
		Path script = Files.writeString(folder.resolve("step.sh"), script(), StandardCharsets.UTF_8);
		// its output goes to a file, so the deadline holds while it is silent
		Path printed = folder.resolve("log");
		ProcessBuilder bash = new ProcessBuilder("bash", "--noprofile", "--norc", "-eo", "pipefail", script.toString())
			.directory(workspace.toFile())
			.redirectErrorStream(true)
			.redirectOutput(printed.toFile());
		bash.environment().putAll(environment(workspace, temp, bin, answers));
		Process process = bash.start();
		boolean ended = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		String log = Files.readString(printed, StandardCharsets.UTF_8);
		if (!ended) {
			process.destroyForcibly();
			throw new IllegalStateException("the check step ran past " + TIMEOUT_SECONDS + " s:\n" + log);
		}
		return new CheckStep(process.exitValue(), outputs(temp), calls(temp),
				Files.readString(temp.resolve("summary"), StandardCharsets.UTF_8), log, workspace, temp);
	}

	/**
	 * The action's own folder for the design, where the container writes, as the step
	 * names it ({@code $RUNNER_TEMP/archsim-check-$slug}).
	 * @return the folder
	 */
	String files() {
		return this.temp + "/archsim-check-" + this.outputs.get("slug");
	}

	/**
	 * The tag the step gave the image it built.
	 * @return the tag
	 */
	String tag() {
		List<String> build = this.docker.getFirst();
		return build.get(build.indexOf("--tag") + 1);
	}

	/**
	 * The arguments the CLI was run with, in the image the step built.
	 * @return the arguments, from {@code check}
	 */
	List<String> check() {
		List<String> run = this.docker.get(1);
		return run.subList(run.indexOf(tag()) + 1, run.size());
	}

	// the check step's own script, from the action
	private static String script() throws IOException {
		Object action = new Yaml().load(Files.readString(ACTION.resolve("action.yml"), StandardCharsets.UTF_8));
		if (action instanceof Map<?, ?> fields && fields.get("runs") instanceof Map<?, ?> runs
				&& runs.get("steps") instanceof List<?> steps) {
			for (Object step : steps) {
				if (step instanceof Map<?, ?> check && "check".equals(check.get("id"))
						&& check.get("run") instanceof String run) {
					return run;
				}
			}
		}
		throw new IllegalStateException("action.yml has no step with the id check");
	}

	// the runner's environment, the action's inputs at their defaults, and the case's
	private static Map<String, String> environment(Path workspace, Path temp, Path bin, Map<String, String> answers) {
		Map<String, String> environment = new HashMap<>();
		environment.put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
		environment.putAll(Map.of("DESIGN", "archsim/design.json", "SCENARIO", "", "BASELINE_REF", "", "FAIL_ON",
				"objectives", "KNEE", "false", "MAX_P95", "0.10", "MAX_COST", "0.10", "ARCHSIM_DIR", "", "REPORT_URL",
				"https://github.com/o/r/actions/runs/7"));
		environment.putAll(Map.of("GITHUB_WORKSPACE", workspace.toString(), "GITHUB_ACTION_PATH", ACTION.toString(),
				"GITHUB_OUTPUT", temp.resolve("output").toString(), "GITHUB_STEP_SUMMARY",
				temp.resolve("summary").toString(), "RUNNER_TEMP", temp.toString(), "RUNNER_OS", "Linux",
				"DOCKER_CALLS", temp.resolve("docker-calls").toString()));
		environment.putAll(answers);
		return environment;
	}

	private static Map<String, String> outputs(Path temp) throws IOException {
		Map<String, String> outputs = new LinkedHashMap<>();
		for (String line : Files.readAllLines(temp.resolve("output"), StandardCharsets.UTF_8)) {
			int equals = line.indexOf('=');
			if (equals >= 0) {
				outputs.put(line.substring(0, equals), line.substring(equals + 1));
			}
		}
		return outputs;
	}

	private static List<List<String>> calls(Path temp) throws IOException {
		return Files.readAllLines(temp.resolve("docker-calls"), StandardCharsets.UTF_8)
			.stream()
			.map((line) -> List.of(line.split(SEPARATOR, -1)))
			.toList();
	}

}
