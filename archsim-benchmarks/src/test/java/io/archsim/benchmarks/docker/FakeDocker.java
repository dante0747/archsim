package io.archsim.benchmarks.docker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Docker as a test plays it: each command is recorded with the variables it was given,
 * and answered by the test.
 */
public final class FakeDocker implements ProcessRunner {

	private final List<String> commands = Collections.synchronizedList(new ArrayList<>());

	private final List<Map<String, String>> environments = Collections.synchronizedList(new ArrayList<>());

	private final Function<String, ProcessResult> answers;

	/**
	 * Docker answering each command, written out with spaces between its words.
	 * @param answers the answer to a command
	 */
	public FakeDocker(Function<String, ProcessResult> answers) {
		this.answers = answers;
	}

	/**
	 * Docker that succeeds at everything and prints nothing.
	 * @return the fake
	 */
	public static FakeDocker silent() {
		return new FakeDocker((command) -> printing(""));
	}

	/**
	 * A command that succeeded, printing something.
	 * @param output what it printed
	 * @return its result
	 */
	public static ProcessResult printing(String output) {
		return new ProcessResult(0, output, "");
	}

	@Override
	public ProcessResult run(List<String> command, Map<String, String> environment) {
		String words = String.join(" ", command);
		this.commands.add(words);
		this.environments.add(Map.copyOf(environment));
		return this.answers.apply(words);
	}

	/**
	 * The commands run so far, each written out with spaces between its words.
	 * @return the commands, in order
	 */
	public List<String> commands() {
		return List.copyOf(this.commands);
	}

	/**
	 * The variables each command was given over the harness's own.
	 * @return the variables, in the commands' order
	 */
	public List<Map<String, String>> environments() {
		return List.copyOf(this.environments);
	}

}
