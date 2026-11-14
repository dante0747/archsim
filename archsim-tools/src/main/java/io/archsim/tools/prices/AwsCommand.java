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
 * {@code prices aws}: a region's snapshot of AWS's prices from the bulk API's offer files
 * ({@link AwsPrices}), which it keeps in a cache.
 */
@Command(name = "aws", mixinStandardHelpOptions = true,
		description = { "Refresh an AWS price snapshot from the AWS Price List bulk API (public, no account needed).",
				"",
				"Reads the region's CSV offer files (the EC2 file is several hundred MB) for EC2, RDS, "
						+ "ElastiCache, MSK, EBS gp3, Lambda, SQS, the load balancers, DynamoDB, API Gateway and the "
						+ "EKS control plane. Only the region's own current-generation on-demand prices are kept. "
						+ "Run by hand; ArchSim never calls it at runtime." })
final class AwsCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--region", required = true, description = "e.g. us-east-1")
	private String region = "";

	@Option(names = "--cache", description = "where the downloaded CSVs are kept (default: ${DEFAULT-VALUE})")
	private Path cache = Path.of(".cache", "aws-prices");

	@Option(names = "--out", description = "output file (default: among archsim-model's snapshots)")
	private @Nullable Path out;

	private final PriceSetup setup;

	AwsCommand(PriceSetup setup) {
		this.setup = setup;
	}

	@Override
	public Integer call() throws IOException {
		Snapshot snapshot = AwsPrices.snapshot(this.region, this.setup.downloads(), this.cache);
		this.setup.writer(this.spec.commandLine().getErr()).write(snapshot, Optional.ofNullable(this.out));
		return 0;
	}

}
