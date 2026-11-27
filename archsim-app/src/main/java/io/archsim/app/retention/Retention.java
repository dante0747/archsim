package io.archsim.app.retention;

import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * Rows a feature keeps only for a while, and how it deletes them once it no longer keeps
 * them: when they expire, or when there are more of them than it keeps. Each feature that
 * stores such rows has one, a bean {@link Pruning} runs every hour in the API role.
 *
 * <p>
 * A retention deletes in batches ({@link #inBatches}), each one statement, so a
 * transaction of its own, which selects its rows {@code FOR UPDATE SKIP LOCKED}: it locks
 * them only while it runs, and leaves the rows another transaction holds to it, so API
 * instances pruning at once share the rows rather than wait for them.
 */
public interface Retention {

	/**
	 * What it prunes, as the log names it.
	 * @return the rows' description, such as {@code expired sessions}
	 */
	String description();

	/**
	 * Deletes the rows it no longer keeps.
	 * @param now the time it is
	 * @return how many rows it pruned
	 */
	int prune(Instant now);

	/**
	 * Prunes batch after batch until one finds nothing left to prune.
	 * @param batch prunes one batch, and says how many rows it pruned
	 * @return how many rows the batches pruned in all
	 */
	static int inBatches(IntSupplier batch) {
		int pruned = 0;
		int rows = batch.getAsInt();
		while (rows > 0) {
			pruned += rows;
			rows = batch.getAsInt();
		}
		return pruned;
	}

}
