package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.cost.ManagedLines.Billed;
import io.archsim.engine.cost.ManagedLines.Billing;
import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.codec.BindingException;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.database.CapacityMode;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.pricing.PriceSnapshot;

/**
 * DynamoDB at a price snapshot's rates: provisioned capacity units per hour, or on-demand
 * request units at the base load, plus its storage.
 */
final class DynamoDbLine {

	private static final String DYNAMODB = "dynamodb";

	/** A key's item size is in KB of this many bytes. */
	private static final double BYTES_PER_KB = 1000;

	/** Bytes in a GiB, 2 to the 30th. */
	private static final double BYTES_PER_GIB = 1L << 30;

	private final PriceSnapshot snapshot;

	private final Map<String, @Nullable Double> rates;

	private final Optional<ComponentSpec<?>> database;

	DynamoDbLine(PriceSnapshot snapshot, ComponentRegistry registry) {
		this.snapshot = snapshot;
		this.rates = snapshot.rates();
		this.database = registry.find(DatabaseSpec.TYPE);
	}

	/**
	 * A DynamoDB table's line.
	 * @param part the part
	 * @param use its usage at the base load; {@code null} when unknown before a run
	 * @param keyCount the workload's keys, for the table's size when it does not say
	 * @return the line; empty for another part, a config that is not valid, or a snapshot
	 * without DynamoDB's rates
	 */
	Optional<CostLine> line(CostPart part, @Nullable Usage use, double keyCount) {
		if (!DatabaseSpec.TYPE.equals(part.type()) || !DYNAMODB.equals(part.technology())) {
			return Optional.empty();
		}
		Optional<DatabaseConfig> config = config(part);
		if (config.isEmpty()) {
			return Optional.empty();
		}
		if (config.get().capacityMode() == CapacityMode.PROVISIONED) {
			return provisioned(part, config.get(), keyCount);
		}
		return onDemand(part, config.get(), use, keyCount);
	}

	private Optional<CostLine> provisioned(CostPart part, DatabaseConfig config, double keyCount) {
		Double rcuHour = this.rates.get("dynamodbRcuHour");
		Double wcuHour = this.rates.get("dynamodbWcuHour");
		if (rcuHour == null || wcuHour == null) {
			return Optional.empty();
		}
		double rcu = config.readCapacityUnits();
		double wcu = config.writeCapacityUnits();
		List<CostItem> items = new ArrayList<>(
				List.of(CostItem.of("RCU-hours", rcu * CostEstimator.HOURS_PER_MONTH, rcuHour, "RCU-hour"),
						CostItem.of("WCU-hours", wcu * CostEstimator.HOURS_PER_MONTH, wcuHour, "WCU-hour")));
		String basis = NumberText.plain(rcu) + " RCU + " + NumberText.plain(wcu) + " WCU provisioned";
		addStorage(items, part, config, keyCount);
		double total = ManagedLines.total(items);
		return Optional.of(priced(part, new Billing(1, total / CostEstimator.HOURS_PER_MONTH, CostUnit.HOUR, total),
				basis, items));
	}

	private Optional<CostLine> onDemand(CostPart part, DatabaseConfig config, @Nullable Usage use, double keyCount) {
		Double readPerMillion = this.rates.get("dynamodbReadPerMillion");
		Double writePerMillion = this.rates.get("dynamodbWritePerMillion");
		if (readPerMillion == null || writePerMillion == null) {
			return Optional.empty();
		}
		if (use == null) {
			return Optional.of(priced(part, new Billing(0, null, CostUnit.MILLION, 0.0),
					"per request unit (run a simulation to price it)", List.of()));
		}
		double perRead = config.unitsPerRead();
		double perWrite = config.unitsPerWrite();
		List<CostItem> items = new ArrayList<>(List.of(
				CostItem.perMillion("read request units", use.read() * perRead * CostEstimator.SECONDS_PER_MONTH,
						readPerMillion, "unit"),
				CostItem.perMillion("write request units", use.write() * perWrite * CostEstimator.SECONDS_PER_MONTH,
						writePerMillion, "unit")));
		String basis = HalfUp.fixed(use.read(), 1) + " reads/s × " + NumberText.plain(perRead) + " unit + "
				+ HalfUp.fixed(use.write(), 1) + " writes/s × " + NumberText.plain(perWrite)
				+ " unit at the base load, on demand";
		addStorage(items, part, config, keyCount);
		double total = ManagedLines.total(items);
		return Optional.of(priced(part, new Billing(0, null, CostUnit.MILLION, total), basis, items));
	}

	/**
	 * The table's storage: its data size, else its keys at its item size.
	 * @param items the line's items, to add it to
	 * @param part the part
	 * @param config its config
	 * @param keyCount the workload's keys
	 */
	private void addStorage(List<CostItem> items, CostPart part, DatabaseConfig config, double keyCount) {
		Double storage = this.rates.get("dynamodbStorageGibMonth");
		Double data = part.writtenNumber("dataSizeGib");
		double size = (data != null) ? data : keyCount * config.itemSizeKb() * BYTES_PER_KB / BYTES_PER_GIB;
		if (storage != null && size > 0) {
			items.add(CostItem.of("GiB-months stored", size, storage, "GiB-month"));
		}
	}

	private CostLine priced(CostPart part, Billing billing, String basis, List<CostItem> items) {
		return ManagedLines.priced(part, new Billed(billing, this.snapshot.snapshotDate()), basis, items);
	}

	private Optional<DatabaseConfig> config(CostPart part) {
		try {
			return this.database.map((spec) -> spec.parseConfig(part.node().config()))
				.filter(DatabaseConfig.class::isInstance)
				.map(DatabaseConfig.class::cast);
		}
		catch (BindingException ex) {
			// a config that is not valid is priced like any other part
			return Optional.empty();
		}
	}

}
