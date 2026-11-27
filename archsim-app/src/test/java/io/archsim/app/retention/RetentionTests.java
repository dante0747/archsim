package io.archsim.app.retention;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruning a batch at a time: batch after batch, until one finds nothing left.
 */
class RetentionTests {

	@Test
	void inBatches_batchesThatPruneRows_goOnUntilOneFindsNone() {
		Deque<Integer> batches = new ArrayDeque<>(List.of(100, 100, 7, 0, 50));

		int pruned = Retention.inBatches(batches::removeFirst);

		assertThat(pruned).isEqualTo(207);
		assertThat(batches).containsExactly(50);
	}

	@Test
	void inBatches_nothingToPrune_runsOneBatch() {
		Deque<Integer> batches = new ArrayDeque<>(List.of(0, 100));

		int pruned = Retention.inBatches(batches::removeFirst);

		assertThat(pruned).isZero();
		assertThat(batches).containsExactly(100);
	}

}
