package io.archsim.tools.release;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Checks a release tag before the release workflow publishes its images (ADR-0077): the
 * tag names the version {@code gradle.properties} says (ADR-0076), and
 * {@code docker-compose.yml} and the hosted kit's {@code deploy/compose.yaml} (ADR-0080)
 * run ArchSim's images from the registry the workflow pushes to, at that version. It
 * writes the version, and whether the release moves {@code latest}, to GitHub's step
 * outputs. It uses the JDK alone, so that the workflow runs it from source, from the
 * checkout's root, before any build:
 *
 * <pre>
 * java archsim-tools/src/main/java/io/archsim/tools/release/CheckRelease.java --tag v1.0.0 \
 *     --registry ghcr.io/dante0747 --github-output "$GITHUB_OUTPUT"
 * </pre>
 */
public final class CheckRelease {

	/** How it is called. */
	static final String USAGE = "usage: CheckRelease --tag TAG --registry REGISTRY [--github-output FILE]";

	private static final int MISUSED = 2;

	private CheckRelease() {
	}

	/**
	 * Checks the tag against the working folder's checkout, and exits with 0 when it can
	 * be released, 1 when it cannot, 2 when the arguments are wrong.
	 * @param args {@code --tag TAG --registry REGISTRY}, and {@code --github-output FILE}
	 */
	public static void main(String[] args) {
		PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
		PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
		System.exit(run(List.of(args), Path.of(""), out, err));
	}

	/**
	 * Checks the tag against a checkout, says what the release publishes, and appends
	 * {@code version} and {@code latest} to GitHub's output file when one is given.
	 * @param arguments the command line's arguments
	 * @param repository the checkout's root
	 * @param out where what the release publishes goes
	 * @param err where each problem goes
	 * @return 0 when the release can go ahead, 1 when it cannot or the checkout cannot be
	 * read, 2 when the arguments are wrong
	 */
	static int run(List<String> arguments, Path repository, PrintWriter out, PrintWriter err) {
		Arguments parsed;
		try {
			parsed = Arguments.of(arguments);
		}
		catch (IllegalArgumentException ex) {
			err.println(USAGE);
			err.println("CheckRelease: error: " + ex.getMessage());
			return MISUSED;
		}
		try {
			Checkout checkout = Checkout.read(repository);
			List<String> problems = checkout.problems(parsed.tag(), parsed.registry());
			problems.forEach(err::println);
			if (!problems.isEmpty()) {
				return 1;
			}
			out.println("release %s, %s latest".formatted(checkout.version(),
					checkout.movesLatest() ? "moving" : "leaving"));
			if (parsed.githubOutput().isPresent()) {
				Files.writeString(parsed.githubOutput().get(), outputs(checkout), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			return 0;
		}
		catch (IOException ex) {
			err.println("CheckRelease: " + ex);
			return 1;
		}
	}

	// GitHub's step outputs: the version, and whether the release moves latest
	private static String outputs(Checkout checkout) {
		return "version=" + checkout.version() + "\nlatest=" + checkout.movesLatest() + "\n";
	}

}
