package io.archsim.engine.components.database;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.TokenBucket;
import io.archsim.model.component.database.CapacityMode;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.document.RequestType;

/**
 * A table's capacity metered in units (DynamoDB-style): each operation takes its units
 * from its partition, which serves at most 3,000 read and 1,000 write units a second,
 * then from the table, provisioned with a burst of five minutes' units or on demand. The
 * units consumed are counted in tenths.
 */
final class CapacityUnits {

	private static final double TENTHS = 10.0;

	private final List<TokenBucket> partitionReads;

	private final List<TokenBucket> partitionWrites;

	private final TokenBucket tableReads;

	private final TokenBucket tableWrites;

	private final double unitsPerRead;

	private final double unitsPerWrite;

	private final NodeMetrics metrics;

	/**
	 * A table's capacity, every bucket full.
	 * @param config the table's settings
	 * @param partitions its partitions
	 * @param metrics its metrics, which count the units consumed
	 */
	CapacityUnits(DatabaseConfig config, int partitions, NodeMetrics metrics) {
		this.partitionReads = buckets(partitions, DatabaseConfig.PARTITION_READ_UNITS);
		this.partitionWrites = buckets(partitions, DatabaseConfig.PARTITION_WRITE_UNITS);
		double reads = config.readUnitsPerSecond();
		double writes = config.writeUnitsPerSecond();
		boolean provisioned = config.capacityMode() == CapacityMode.PROVISIONED;
		this.tableReads = TokenBucket.of(reads, provisioned ? reads * DatabaseConfig.BURST_SECONDS : reads);
		this.tableWrites = TokenBucket.of(writes, provisioned ? writes * DatabaseConfig.BURST_SECONDS : writes);
		this.unitsPerRead = config.unitsPerRead();
		this.unitsPerWrite = config.unitsPerWrite();
		this.metrics = metrics;
	}

	/**
	 * Takes an operation's units from its partition, then from the table.
	 * @param partition the partition
	 * @param operation a read or a write
	 * @param now the time
	 * @return {@code false} when either has too few left, which throttles the operation
	 */
	boolean take(int partition, RequestType operation, double now) {
		boolean read = operation == RequestType.READ;
		double units = read ? this.unitsPerRead : this.unitsPerWrite;
		TokenBucket partitionUnits = (read ? this.partitionReads : this.partitionWrites).get(partition);
		TokenBucket tableUnits = read ? this.tableReads : this.tableWrites;
		if (!partitionUnits.tryTake(now, units) || !tableUnits.tryTake(now, units)) {
			return false;
		}
		this.metrics.count(counter(operation), now, Math.round(units * TENTHS));
		return true;
	}

	/**
	 * The units a table consumed per second in each bucket.
	 * @param metrics the table's metrics
	 * @param operation reads or writes
	 * @return the units, one value per bucket of the whole timeline; zeros when it
	 * consumed none
	 */
	static List<@Nullable Double> consumedPerSecond(NodeMetrics metrics, RequestType operation) {
		Counter tenths = metrics.counters().get(counter(operation));
		double[] rates = (tenths != null) ? tenths.rateSeries() : new double[metrics.timeline().buckets()];
		return RuntimeComponent.series(Arrays.stream(rates).map((rate) -> rate / TENTHS).toArray());
	}

	private static String counter(RequestType operation) {
		return (operation == RequestType.READ) ? DatabaseCounters.READ_UNITS_X10 : DatabaseCounters.WRITE_UNITS_X10;
	}

	private static List<TokenBucket> buckets(int partitions, double unitsPerSecond) {
		return IntStream.range(0, partitions)
			.mapToObj((partition) -> TokenBucket.of(unitsPerSecond, unitsPerSecond))
			.toList();
	}

}
