package io.archsim.benchmarks.range;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.parallel.InParallel;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.text.JsonDecodeException;

/**
 * The work a range fit has finished, kept in a file outside the repository, so that a fit
 * cut short starts again where it stopped: each task's result under its key, the file
 * replaced whole as each one arrives, so that a stop mid-write leaves the one before.
 */
final class RangeCache {

	/** How often the cache says how far it has got: every tenth task, and the last. */
	private static final int PROGRESS_EVERY = 10;

	private final Path file;

	private final ObjectNode results;

	private final HarnessLog log;

	private int finished;

	private RangeCache(Path file, ObjectNode results, HarnessLog log) {
		this.file = file;
		this.results = results;
		this.log = log;
	}

	/**
	 * The cache in a file, with what it holds already; a file that is not a cache's is
	 * left for the next write to replace.
	 * @param file the file, which need not be there yet
	 * @param log the log: what the cache holds, and a line every tenth task
	 * @return the cache
	 */
	static RangeCache in(Path file, HarnessLog log) {
		JsonNode held;
		try {
			held = ResultFiles.read(file).orElse(JsonNodeFactory.instance.objectNode());
		}
		catch (JsonDecodeException ex) {
			log.line("range fit: " + file + " is not a cache (" + ex.getMessage() + "): starting afresh");
			held = JsonNodeFactory.instance.objectNode();
		}
		ObjectNode results = held.isObject() ? (ObjectNode) held : JsonNodeFactory.instance.objectNode();
		if (!results.isEmpty()) {
			log.line("range fit: reusing the " + results.size() + " results " + file + " holds");
		}
		return new RangeCache(file, results, log);
	}

	/**
	 * The result of a key.
	 * @param key the key
	 * @return the result; empty when the cache holds none
	 */
	synchronized Optional<JsonNode> get(String key) {
		return Optional.ofNullable(this.results.get(key));
	}

	/**
	 * Whether the cache holds a key's result.
	 * @param key the key
	 * @return whether it does
	 */
	synchronized boolean has(String key) {
		return this.results.has(key);
	}

	/**
	 * Runs the tasks the cache holds no result for, on every processor, keeping each
	 * result in the file as it arrives.
	 * @param tasks the tasks
	 * @throws RuntimeException the first failure in the tasks' order, after the work not
	 * yet started is cancelled; the results that arrived before it stay in the file, so a
	 * fit run again resumes after it. A move over the file that another program holds
	 * open without sharing it (a scanner, on Windows) is such a failure.
	 */
	void run(List<Task> tasks) {
		List<Task> todo = tasks.stream().filter((task) -> !has(task.key())).toList();
		synchronized (this) {
			this.finished = 0;
		}
		InParallel.map(todo, (task) -> {
			keep(task.key(), task.work().get(), todo.size());
			return task.key();
		});
	}

	private synchronized void keep(String key, JsonNode result, int tasks) {
		this.results.set(key, result);
		write();
		this.finished++;
		if (this.finished % PROGRESS_EVERY == 0 || this.finished == tasks) {
			this.log.line("range fit: " + this.finished + " of " + tasks + " done");
		}
	}

	// the whole cache to a file beside it, then that file over the cache in one move
	private void write() {
		Path written = this.file.resolveSibling(this.file.getFileName() + ".writing");
		try {
			Files.writeString(written, ResultFiles.text(this.results), StandardCharsets.UTF_8);
			Files.move(written, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A piece of the fit's work.
	 *
	 * @param key what the cache keeps its result under
	 * @param work the work
	 */
	record Task(String key, Supplier<JsonNode> work) {

	}

}
