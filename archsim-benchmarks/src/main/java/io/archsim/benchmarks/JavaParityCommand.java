package io.archsim.benchmarks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.parity.JavaParity;

/**
 * {@code java-parity}: writes {@code docs/validation/java-parity.md}, the Java simulator
 * against the frozen engine by stage 4's bar; with {@code --check}, fails when the
 * committed page is not the one its inputs give.
 */
@Command(name = "java-parity",
		description = "write docs/validation/java-parity.md, the Java simulator against the frozen engine 0.16.0",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class JavaParityCommand implements Callable<Integer> {

	private static final String PAGE = "java-parity.md";

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--check", description = "fail if the page is stale")
	private boolean check;

	private final RepositoryLayout layout;

	JavaParityCommand(RepositoryLayout layout) {
		this.layout = layout;
	}

	@Override
	public Integer call() {
		JavaParity parity = new JavaParity(this.layout.root());
		List<String> missing = parity.missing();
		if (!missing.isEmpty()) {
			this.spec.commandLine().getErr().println(String.join("\n", missing));
			return 1;
		}
		Path page = this.layout.validation().resolve(PAGE);
		return this.check ? checkPage(page, parity.page()) : write(page, parity.page());
	}

	private int checkPage(Path page, String text) {
		try {
			if (Files.exists(page) && Files.readString(page, StandardCharsets.UTF_8).equals(text)) {
				return 0;
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		this.spec.commandLine().getErr().println(PAGE + " is stale: run " + JavaParity.command());
		return 1;
	}

	private int write(Path page, String text) {
		try {
			Files.writeString(page, text, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		this.spec.commandLine().getOut().println("wrote " + this.layout.name(page));
		return 0;
	}

}
