package io.archsim.engine.components.database;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.document.RequestType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A table's capacity metered in units, as the Python engine 0.16.0 takes them: from the
 * partition, then from the table.
 */
class CapacityUnitsTests {

	private final NodeMetrics metrics = new NodeMetrics(new Timeline(1000, 0, 1000), OptionalInt.empty());

	@Test
	void take_aProvisionedTableWithUnitsLeft_countsTheUnitsInTenths() {
		CapacityUnits units = units("{\"capacityMode\": \"provisioned\", \"readCapacityUnits\": 1}");

		boolean taken = units.take(0, RequestType.READ, 0);

		assertThat(taken).isTrue();
		assertThat(this.metrics.counters()).hasEntrySatisfying(DatabaseCounters.READ_UNITS_X10,
				(tenths) -> assertThat(tenths.total()).isEqualTo(5));
	}

	@Test
	void take_aProvisionedTablesBurstSpent_throttles() {
		CapacityUnits units = units("{\"capacityMode\": \"provisioned\", \"writeCapacityUnits\": 1}");
		for (int write = 0; write < 300; write++) {
			units.take(0, RequestType.WRITE, 0);
		}

		boolean taken = units.take(0, RequestType.WRITE, 0);

		assertThat(taken).isFalse();
	}

	@Test
	void take_aPartitionsUnitsSpent_throttlesWithoutSpendingTheTables() {
		// a burst of 4 units a second for 300 s: three writes of 400 units
		CapacityUnits units = units(
				"{\"capacityMode\": \"provisioned\", \"writeCapacityUnits\": 4, \"itemSizeKb\": 400}", 2);
		units.take(0, RequestType.WRITE, 0);
		units.take(0, RequestType.WRITE, 0);

		boolean taken = units.take(0, RequestType.WRITE, 0);

		assertThat(taken).isFalse();
		assertThat(units.take(1, RequestType.WRITE, 0)).as("the table's last units").isTrue();
	}

	private CapacityUnits units(String json) {
		return units(json, 1);
	}

	private CapacityUnits units(String json, int partitions) {
		DatabaseConfig config = RecordCodec.read(JsonMapper.builder().build().readTree(json), DatabaseConfig.class);
		return new CapacityUnits(config, partitions, this.metrics);
	}

}
