package io.archsim.engine.cost;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.document.Workload;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSource;

/** The cost estimate's notes: how it was priced and what it leaves out, in order. */
final class CostNotes {

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
			notes.add(perRequest(context.month(), context.base()));
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

	/**
	 * How per-request prices and the cost per request count the month: all of it at the
	 * base load, or its peak hours at the peak and the rest at the base load.
	 * @param month the workload and the hours of the month billed at its peak
	 * @param base each part's usage at the base load: estimated, or measured in a run
	 * @return the note
	 */
	private static String perRequest(BilledMonth month, LevelLoad base) {
		Workload workload = month.workload();
		String baseRps = HalfUp.fixed(LoadLevel.BASE.rate(workload), 1);
		if (month.peakHours() == 0) {
			return "Per-request prices and cost per request are at the steady base load of " + baseRps
					+ " req/s as if it lasted all " + CostEstimator.HOURS_PER_MONTH + " hours of a month: "
					+ how(base, "the base", "that") + ". A burst's or ramp's peak is not billed all month.";
		}
		return "Per-request prices and cost per request are at the steady base load of " + baseRps + " req/s for "
				+ hours(CostEstimator.HOURS_PER_MONTH - month.peakHours()) + " of a month and at the peak of "
				+ HalfUp.fixed(LoadLevel.PEAK.rate(workload), 1) + " req/s for " + hours(month.peakHours()) + ": "
				+ how(base, "each of them", "each") + ".";
	}

	/**
	 * How each part's rate at a load was found.
	 * @param base each part's usage at the base load: estimated, or measured in a run
	 * @param where the load or loads a run measured at, such as {@code the base}
	 * @param which the rate or rates estimated at, such as {@code that}
	 * @return the words
	 */
	private static String how(LevelLoad base, String where, String which) {
		return switch (base) {
			case LevelLoad.Estimated _ ->
				"the static load estimate at " + which + " rate (run a simulation to measure it)";
			case LevelLoad.Measured _ -> "each part's rate measured while the run's offered load was at " + where
					+ ", scaled to it exactly, so fan-out, cache misses and retries are kept";
		};
	}

	private static String hours(double hours) {
		return CostText.plural(HalfUp.round(hours, 1), "hour");
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
	 * @param month the workload and the hours of the month billed at its peak
	 * @param perRequest whether a line is priced per request and costs something, or the
	 * cost per million requests is known
	 * @param base each part's usage at the base load: estimated, or measured in a run
	 */
	record Context(PriceSnapshot snapshot, String option, BilledMonth month, boolean perRequest, LevelLoad base) {
	}

	/**
	 * The month a cost bills.
	 *
	 * @param workload the workload, with its base load and its peak
	 * @param peakHours the hours of the month billed at the peak
	 */
	record BilledMonth(Workload workload, double peakHours) {
	}

}
