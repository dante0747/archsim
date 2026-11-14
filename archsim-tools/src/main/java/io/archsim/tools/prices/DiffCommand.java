package io.archsim.tools.prices;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * {@code prices diff}: what changed between two snapshots ({@link PriceDiff}), the
 * refresh's pull request body. Under GitHub Actions it also tells the workflow whether
 * there is anything to propose.
 */
@Command(name = "diff", mixinStandardHelpOptions = true,
		description = { "Summarize what changed between two price snapshots, as Markdown.", "",
				"Without --against, the newest older snapshot of the same provider and region in the new file's "
						+ "folder is the baseline. Under GitHub Actions it also writes changed=true|false to "
						+ "$GITHUB_OUTPUT (false: the same prices as the baseline, only the date differs)." })
final class DiffCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Parameters(paramLabel = "NEW", description = "the new snapshot")
	private Path current = Path.of("");

	@Option(names = "--against", paramLabel = "OLD",
			description = "the snapshot to compare with (default: the newest older one)")
	private @Nullable Path against;

	private final PriceSetup setup;

	DiffCommand(PriceSetup setup) {
		this.setup = setup;
	}

	@Override
	public Integer call() throws IOException {
		JsonNode snapshot = read(this.current);
		Optional<Path> baseline = (this.against != null) ? Optional.of(this.against)
				: Baselines.baselineFor(this.current);
		Optional<JsonNode> old = baseline.isPresent() ? Optional.of(read(baseline.get())) : Optional.empty();
		PriceDiff.Summary summary = PriceDiff.summarize(old, snapshot);
		PrintWriter out = this.spec.commandLine().getOut();
		out.print(summary.text());
		out.flush();
		String output = this.setup.environment().getOrDefault("GITHUB_OUTPUT", "");
		if (!output.isEmpty()) {
			Files.writeString(Path.of(output), "changed=" + summary.changed() + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		return 0;
	}

	private static JsonNode read(Path snapshot) throws IOException {
		return PythonJsonReader.read(Files.readString(snapshot, StandardCharsets.UTF_8));
	}

}
