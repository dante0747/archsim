package io.archsim.engine.cost;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSource;

/** The cost estimate's notes: how it was priced and what it leaves out, in order. */
final class CostNotes {

	private static final String MEASURED = "each part's rate measured while the run's offered load was at the base, "
			+ "scaled to it exactly, so fan-out, cache misses and retries are kept";

	private static final String ESTIMATED = "the static load estimate at that rate (run a simulation to measure it)";

	private CostNotes() {
	}

	/**
	 * Adds the notes, after any note on the snapshot itself.
	 * @param notes the notes so far
	 * @param flags what the pricing found
	 * @param context the snapshot, purchase option, base load, whether any cost is per
	 * request, and how each part's rate at the base load was found
	 */
	static void add(List<String> notes, CostFlags flags, Context context) {
		PriceSnapshot snapshot = context.snapshot();
		if (!flags.priced()) {
			notes.add("No component has a price yet: set Cost per hour on components to estimate cost.");
		}
		if (flags.snapshotPriced()) {
			notes.add(prices(snapshot, context.option()));
		}
		if (flags.presetKept()) {
			notes.add("Parts the price snapshot does not cover keep their technology preset's illustrative price.");
		}
		if (flags.sizePriced()) {
			// whenever any line is priced at the generic rates, mixed with snapshot lines
			// or not
			notes.add(sizes(snapshot));
		}
		if (flags.missingCallRate()) {
			notes.add("Third-party APIs are priced per call: run a simulation to price them at the measured call "
					+ "rate.");
		}
		if (context.perRequest()) {
			notes.add(perRequest(context.baseRps(), context.base()));
		}
		if (flags.snapshotPriced()) {
			notes.add(CostEstimator.NOT_MODELLED);
		}
	}

	private static String sizes(PriceSnapshot snapshot) {
		return (snapshot.illustrative() ? "Parts without a set price are"
				: "Parts the price snapshot does not cover are")
				+ " priced from their size at illustrative on-demand rates ($"
				+ NumberText.plain(GenericRates.VCPU_HOUR) + " per vCPU-hour, $"
				+ NumberText.plain(GenericRates.GIB_HOUR) + " per GiB-hour, $"
				+ NumberText.plain(GenericRates.STORAGE_GIB_MONTH) + " per GiB-month of storage).";
	}

	private static String perRequest(double baseRps, LevelLoad base) {
		String how = switch (base) {
			case LevelLoad.Estimated _ -> ESTIMATED;
			case LevelLoad.Measured _ -> MEASURED;
		};
		return "Per-request prices and cost per request are at the steady base load of " + HalfUp.fixed(baseRps, 1)
				+ " req/s as if it lasted all " + CostEstimator.HOURS_PER_MONTH + " hours of a month: " + how
				+ ". A burst's or ramp's peak is not billed all month.";
	}

	private static String prices(PriceSnapshot snapshot, String option) {
		String names = snapshot.sources().stream().map(PriceSource::name).collect(Collectors.joining(", "));
		// a missing region or date reads None, as Python formats it
		return "Prices: " + snapshot.provider() + " " + Objects.toString(snapshot.region(), "None") + ", "
				+ option.replace('_', '-') + " list prices of " + Objects.toString(snapshot.snapshotDate(), "None")
				+ " (" + names + "). Each part runs on the cheapest instance type that covers its CPU and memory (a "
				+ "burstable t4g type only when its CPU at the base load stays under its baseline, or when its "
				+ "family is named).";
	}

	/**
	 * What the notes depend on beyond the flags.
	 *
	 * @param snapshot the price snapshot
	 * @param option the purchase option
	 * @param baseRps the workload's steady base load
	 * @param perRequest whether a line is priced per request and costs something, or the
	 * cost per million requests is known
	 * @param base each part's usage at the base load: estimated, or measured in a run
	 */
	record Context(PriceSnapshot snapshot, String option, double baseRps, boolean perRequest, LevelLoad base) {
	}

}
