package io.archsim.benchmarks.campaign;

import java.nio.file.Path;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.inputs.ModelInputs;

/**
 * What a campaign runs its experiments with besides their stacks.
 *
 * @param inputs the inputs' derivation, and the engine's simulations it runs on, in
 * process
 * @param prediction what predicts each experiment
 * @param summaries the folder k6's summaries land in ({@code results/.k6})
 * @param log the log
 */
public record CampaignTools(ModelInputs inputs, Prediction prediction, Path summaries, HarnessLog log) {

}
