package io.archsim.tools.prices;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code prices azure}: a region's snapshot of Azure's virtual machine prices
 * ({@link AzurePrices}).
 */
@Command(name = "azure", mixinStandardHelpOptions = true,
		description = {
				"Refresh an Azure price snapshot (virtual machines) from the Azure Retail Prices API "
						+ "(public, no account needed).",
				"", "Reads pay-as-you-go Linux prices of the D v3-v6, E v3-v6 and F v2 series, with memory from each "
						+ "series' published ratio. Run by hand; ArchSim never calls it at runtime." })
final class AzureCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--region", required = true, description = "an ARM region name, e.g. eastus")
	private String region = "";

	@Option(names = "--out", description = "output file (default: among archsim-model's snapshots)")
	private @Nullable Path out;

	private final PriceSetup setup;

	AzureCommand(PriceSetup setup) {
		this.setup = setup;
	}

	@Override
	public Integer call() throws IOException {
		Snapshot snapshot = AzurePrices.snapshot(this.region, AzurePrices.items(this.setup.downloads(), this.region));
		this.setup.writer(this.spec.commandLine().getErr()).write(snapshot, Optional.ofNullable(this.out));
		return 0;
	}

}
