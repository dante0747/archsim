package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The validation page's environment: the machine of each experiment's latest run (one
 * line while they all ran on the same one), the images, and the CPUs each stack's
 * containers were pinned to, as {@code environment.json} recorded them or, for runs
 * before it did, the runner's defaults.
 */
final class EnvironmentBlock {

	private static final double BYTES_PER_GIB = 1L << 30;

	private EnvironmentBlock() {
	}

	/**
	 * The environment of the latest runs.
	 * @param environments each experiment's {@code environment.json}, in the page's order
	 * @param cpusets the cpusets {@code compose.yaml} declares
	 * @return the block
	 */
	static String of(Map<String, JsonNode> environments, Cpusets cpusets) {
		if (environments.isEmpty()) {
			return "_No results yet._";
		}
		JsonNode first = environments.values().iterator().next();
		List<String> lines = new ArrayList<>(machines(environments));
		List<String> images = new ArrayList<>();
		first.path("images")
			.properties()
			.forEach((image) -> images.add(image.getKey() + " `" + PythonText.str(image.getValue()) + "`"));
		lines.add("- Images: " + String.join(", ", images) + ".");
		lines.add(pinning(environments, cpusets));
		return String.join("\n", lines);
	}

	private static List<String> machines(Map<String, JsonNode> environments) {
		Map<String, List<String>> byMachine = new LinkedHashMap<>();
		environments.forEach((experiment, environment) -> byMachine
			.computeIfAbsent(machineText(environment), (machine) -> new ArrayList<>())
			.add(experiment));
		if (byMachine.size() == 1) {
			return List.of("- CPU: " + byMachine.keySet().iterator().next() + ".");
		}
		List<String> lines = new ArrayList<>();
		byMachine.forEach((machine, experiments) -> lines
			.add("- CPU for " + Figures.andList(experiments) + ": " + machine + "."));
		return lines;
	}

	private static String pinning(Map<String, JsonNode> environments, Cpusets cpusets) {
		Map<Pinning, List<String>> byPinning = new LinkedHashMap<>();
		boolean recorded = true;
		for (Map.Entry<String, JsonNode> run : environments.entrySet()) {
			String stack = Experiments.standard().get(run.getKey()).stack();
			JsonNode sets = PythonValues.get(run.getValue(), "cpusets");
			recorded = recorded && !PythonValues.isNone(sets);
			Pinning pinning = new Pinning(stack, cpusText(stackCpusets(stack, sets, cpusets)));
			byPinning.computeIfAbsent(pinning, (key) -> new ArrayList<>()).add(run.getKey());
		}
		List<String> stacks = new ArrayList<>();
		byPinning.forEach((pinning, experiments) -> stacks.add(Figures.andList(experiments) + ": " + pinning.cpus()));
		String note = recorded ? ""
				: " These are the runner's defaults (`benchmarks/run.py`, `compose.yaml`): the runs "
						+ "shown predate recording the cpusets in `environment.json`.";
		return "- CPUs pinned per container (cpuset; k6 gets the CPUs the stack leaves free): "
				+ String.join("; ", stacks) + "." + note;
	}

	/**
	 * The CPUs each container of an experiment's stack was pinned to: as recorded, or the
	 * runner's defaults for runs before it recorded them.
	 * @param stack the experiment's stack
	 * @param recorded the run's {@code cpusets}, or None
	 * @param cpusets the cpusets {@code compose.yaml} declares
	 * @return each container's cpuset
	 */
	static Map<String, String> stackCpusets(String stack, JsonNode recorded, Cpusets cpusets) {
		if (PythonValues.isNone(recorded)) {
			return cpusets.of(stack, Map.of());
		}
		Map<String, String> sets = new LinkedHashMap<>();
		recorded.properties().forEach((set) -> sets.put(set.getKey(), set.getValue().stringValue()));
		return sets;
	}

	// service 1 (`2`), PostgreSQL 2 (`4,5`), k6 4 (`0,1,6,7`)
	private static String cpusText(Map<String, String> sets) {
		List<String> each = new ArrayList<>();
		sets.forEach((name, cpus) -> each
			.add(PageTexts.standard().container(name) + " " + Cpusets.count(cpus) + " (`" + cpus + "`)"));
		return String.join(", ", each);
	}

	/**
	 * The machine a run ran on: its CPU; Docker's version, CPUs and memory; its OS, and
	 * its power mode when recorded.
	 * @param environment the run's {@code environment.json}
	 * @return the machine, in words
	 */
	static String machineText(JsonNode environment) {
		JsonNode powerMode = PythonValues.get(environment, "powerMode");
		String mode = PythonText.truthy(powerMode) ? " (" + PythonText.str(powerMode) + ")" : "";
		return PythonText.str(PythonValues.get(environment, "cpu")) + "; Docker "
				+ PythonText.str(PythonValues.get(environment, "docker")) + " with "
				+ PythonText.str(PythonValues.get(environment, "dockerCpus")) + " CPUs and "
				+ PythonFormat.format(dockerMemoryGib(environment), ".1f") + " GiB; "
				+ PythonText.str(PythonValues.get(environment, "os")) + mode;
	}

	/**
	 * Docker's memory in GiB, as {@code int(env.get("dockerMemory") or 0) / 2**30} reads
	 * it.
	 * @param environment the run's {@code environment.json}
	 * @return the GiB
	 */
	static double dockerMemoryGib(JsonNode environment) {
		JsonNode memory = PythonValues.get(environment, "dockerMemory");
		if (!PythonText.truthy(memory)) {
			return 0.0;
		}
		long bytes = memory.isString() ? Long.parseLong(memory.stringValue().strip()) : memory.longValue();
		return bytes / BYTES_PER_GIB;
	}

	/**
	 * A stack and its containers' cpusets in words: the runs that share both are listed
	 * together.
	 *
	 * @param stack the stack
	 * @param cpus its containers' cpusets, in words
	 */
	private record Pinning(String stack, String cpus) {

	}

}
