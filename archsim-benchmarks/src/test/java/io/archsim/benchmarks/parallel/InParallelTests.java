package io.archsim.benchmarks.parallel;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Work on each item in threads of its own, its results gathered in the items' order, and
 * the first failure in that order thrown once the rest is stopped.
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class InParallelTests {

	private static final int WORKERS = 2;

	// the first item waits until the second is done
	@Test
	void map_itemsThatFinishOutOfOrder_areInTheItemsOrder() {
		CountDownLatch secondDone = new CountDownLatch(1);

		List<String> results = InParallel.map(List.of(0, 1), (item) -> {
			if (item == 0) {
				await(secondDone);
				return "first";
			}
			secondDone.countDown();
			return "second";
		}, WORKERS);

		assertThat(results).containsExactly("first", "second");
	}

	// the second item fails first, then the first
	@Test
	void map_twoFailingItems_throwsTheFirstInTheItemsOrder() {
		CountDownLatch secondFailed = new CountDownLatch(1);
		Function<Integer, String> work = (item) -> {
			if (item == 0) {
				await(secondFailed);
				throw new IllegalStateException("the first item failed");
			}
			secondFailed.countDown();
			throw new IllegalStateException("the second item failed");
		};

		assertThatIllegalStateException().isThrownBy(() -> InParallel.map(List.of(0, 1), work, WORKERS))
			.withMessage("the first item failed");
	}

	// the first item fails; the others wait for good, so they end only when stopped. The
	// worker the failure frees may take one more item before the rest is cancelled, which
	// then waits too: of eight items, three start at most
	@Test
	void map_aFailure_startsNoMoreWorkAndStopsTheWorkRunning() {
		AtomicInteger started = new AtomicInteger();
		CountDownLatch never = new CountDownLatch(1);
		List<Integer> items = IntStream.range(0, 4 * WORKERS).boxed().toList();
		Function<Integer, String> work = (item) -> {
			started.incrementAndGet();
			if (item == 0) {
				throw new IllegalStateException("the first item failed");
			}
			await(never);
			return "done";
		};

		assertThatIllegalStateException().isThrownBy(() -> InParallel.map(items, work, WORKERS))
			.withMessage("the first item failed");
		assertThat(started).hasValueLessThanOrEqualTo(WORKERS + 1);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("stopped", ex);
		}
	}

}
