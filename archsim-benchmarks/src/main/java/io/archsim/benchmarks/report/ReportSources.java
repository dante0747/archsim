package io.archsim.benchmarks.report;

import java.nio.file.Path;

import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Engines;

/**
 * What the reports are made from.
 *
 * @param results the results folder
 * @param engines the engines, which label each run's sample
 * @param cpusets the cpusets {@code compose.yaml} declares: the runner's defaults, for
 * runs that did not record theirs
 */
record ReportSources(Path results, Engines engines, Cpusets cpusets) {

}
