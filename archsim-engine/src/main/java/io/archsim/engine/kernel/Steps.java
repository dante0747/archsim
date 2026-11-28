package io.archsim.engine.kernel;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * Steps taken one after another, each of which finishes at once or later, as a routine's
 * continuations take them. When a step finishes at once, the next starts in a loop rather
 * than from inside the one that finished, so a long run of steps that take no time, such
 * as the messages of a poll a consumer works on for 0 ms, needs no deeper Java stack than
 * one step; a step that finishes later starts the rest when it finishes. A step must
 * finish exactly once, and last, in tail position: then each step starts exactly when
 * starting it from the last one's finish would have started it, and the order of events
 * is the same (ADR-0114). A second finish would start a second run of the steps.
 *
 * @param <T> what each step works on
 */
public final class Steps<T> {

	private final Supplier<@Nullable T> items;

	private final BiConsumer<T, Runnable> step;

	private final Runnable done;

	/** Whether a step is being started now, so a finish is one at once. */
	private boolean starting;

	/** Whether the step being started finished at once. */
	private boolean finishedAtOnce;

	private Steps(Supplier<@Nullable T> items, BiConsumer<T, Runnable> step, Runnable done) {
		this.items = items;
		this.step = step;
		this.done = done;
	}

	/**
	 * Takes a step for each item, in turn, then goes on with what follows.
	 * @param <T> what each step works on
	 * @param items the next item each time it is asked, read when the step before has
	 * finished; {@code null} once there is none
	 * @param step takes a step on an item, and runs what it is given once, last, when the
	 * step has finished
	 * @param done what follows the last step
	 */
	public static <T> void each(Supplier<@Nullable T> items, BiConsumer<T, Runnable> step, Runnable done) {
		new Steps<>(items, step, done).resume();
	}

	private void resume() {
		T item = this.items.get();
		while (item != null) {
			this.finishedAtOnce = false;
			this.starting = true;
			this.step.accept(item, this::finished);
			this.starting = false;
			if (!this.finishedAtOnce) {
				// it finishes later, and resumes the steps then
				return;
			}
			item = this.items.get();
		}
		this.done.run();
	}

	private void finished() {
		if (this.starting) {
			this.finishedAtOnce = true;
			return;
		}
		resume();
	}

}
