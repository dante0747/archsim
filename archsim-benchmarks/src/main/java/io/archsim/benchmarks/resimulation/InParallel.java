package io.archsim.benchmarks.resimulation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Work on each of some items in threads of their own, one per processor at most, its
 * results gathered in the items' order.
 */
final class InParallel {

	private InParallel() {
	}

	/**
	 * Each item's result.
	 * @param <T> the items' type
	 * @param <R> the results' type
	 * @param items the items
	 * @param work the work on one item
	 * @return the results, in the items' order
	 * @throws RuntimeException the first failure in the items' order, after the work not
	 * yet started is cancelled
	 */
	static <T, R> List<R> map(List<T> items, Function<T, R> work) {
		return map(items, work, Runtime.getRuntime().availableProcessors());
	}

	/**
	 * Each item's result, on at most so many threads.
	 * @param <T> the items' type
	 * @param <R> the results' type
	 * @param items the items
	 * @param work the work on one item
	 * @param threads the most threads to work on
	 * @return the results, in the items' order
	 * @throws RuntimeException the first failure in the items' order, after the work not
	 * yet started is cancelled and the work running is interrupted
	 */
	static <T, R> List<R> map(List<T> items, Function<T, R> work, int threads) {
		int workers = Math.max(1, Math.min(items.size(), threads));
		try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
			List<Future<R>> futures = items.stream().map((item) -> pool.submit(() -> work.apply(item))).toList();
			List<R> results = new ArrayList<>();
			for (Future<R> future : futures) {
				results.add(result(future, pool));
			}
			return results;
		}
	}

	private static <R> R result(Future<R> future, ExecutorService pool) {
		try {
			return future.get();
		}
		catch (ExecutionException ex) {
			pool.shutdownNow();
			if (ex.getCause() instanceof RuntimeException failure) {
				throw failure;
			}
			if (ex.getCause() instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(ex.getCause());
		}
		catch (InterruptedException ex) {
			pool.shutdownNow();
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for a simulation", ex);
		}
	}

}
