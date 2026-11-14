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
 * {@code prices gcp}: a region's snapshot of GCP's Compute Engine machine prices
 * ({@link GcpPrices}). The catalog needs an API key, any Google Cloud project's, read
 * from {@code GOOGLE_API_KEY} and never written anywhere.
 */
@Command(name = "gcp", mixinStandardHelpOptions = true,
		description = {
				"Refresh a GCP price snapshot (Compute Engine machine types) from the Cloud Billing " + "Catalog API.",
				"", "Needs an API key in GOOGLE_API_KEY. Prices N2, N2D, E2 and C3 machines as vCPU x the core price "
						+ "+ memory x the RAM price. Run by hand; ArchSim never calls it at runtime." })
final class GcpCommand implements Callable<Integer> {

	private static final String KEY = "GOOGLE_API_KEY";

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--region", required = true, description = "e.g. us-central1")
	private String region = "";

	@Option(names = "--out", description = "output file (default: among archsim-model's snapshots)")
	private @Nullable Path out;

	private final PriceSetup setup;

	GcpCommand(PriceSetup setup) {
		this.setup = setup;
	}

	@Override
	public Integer call() throws IOException {
		String key = this.setup.environment().getOrDefault(KEY, "");
		if (key.isEmpty()) {
			this.spec.commandLine().getErr().println("set " + KEY + " (the Cloud Billing Catalog API needs one)");
			return 1;
		}
		Snapshot snapshot = GcpPrices.snapshot(this.region, GcpPrices.skus(this.setup.downloads(), key));
		this.setup.writer(this.spec.commandLine().getErr()).write(snapshot, Optional.ofNullable(this.out));
		return 0;
	}

}
