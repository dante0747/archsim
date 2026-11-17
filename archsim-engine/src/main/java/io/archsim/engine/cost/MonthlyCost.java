package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.text.HalfUp;

/**
 * A design's month (ADR-0074): the hours its workload says it runs at its peak, billed at
 * the peak, and the rest of its 730 hours at the base load, each line put together from
 * its two loads, with an hour at each beside the month.
 */
final class MonthlyCost {

	private static final double HOURS = CostEstimator.HOURS_PER_MONTH;

	private static final String AT_THE_PEAK = " at the peak";

	/** What follows a line's count in its basis: a note, or where its pods run. */
	private static final List<String> AFTER_THE_COUNT = List.of("; ", " on implied ");

	private MonthlyCost() {
	}

	/**
	 * The month of a design priced at its base load and at its peak.
	 * @param base the design priced at the base load
	 * @param peak the design priced at the peak; the base for a pattern whose peak is its
	 * base
	 * @param peakHours the hours of the month billed at the peak; zero bills the whole
	 * month at the base load
	 * @return the cost
	 * @throws IllegalStateException when the two loads price a part's line with other
	 * items
	 */
	static CostEstimate of(PricedLoad base, PricedLoad peak, double peakHours) {
		// two parts share an id only in a design that is not valid (validation says so),
		// which is still priced: the first one's line at the peak stands for both
		Map<String, CostLine> atPeak = peak.lines()
			.stream()
			.collect(Collectors.toMap(CostLine::id, Function.identity(), (first, second) -> first));
		Month month = new Month(peakHours);
		List<CostLine> lines = base.lines()
			.stream()
			.map((line) -> month.line(line, atPeak.getOrDefault(line.id(), line)))
			.collect(Collectors.toCollection(ArrayList::new));
		double monthly = base.monthly();
		if (peakHours > 0) {
			lines.sort(CostLine::dearestFirst);
			monthly = FloatSum.of(lines.stream().mapToDouble(CostLine::monthly));
		}
		HourlyCost hourly = new HourlyCost(base.monthly() / HOURS, peak.monthly() / HOURS);
		return new CostEstimate(monthly, month.perMillion(monthly, base, peak), peakHours, hourly, lines,
				base.unpriced(), base.notes(), base.hasCost(), "USD", CostEstimator.HOURS_PER_MONTH, base.rates(),
				base.pricing());
	}

	/**
	 * A month of so many hours at the peak and the rest at the base load.
	 *
	 * @param peakHours the hours at the peak
	 */
	private record Month(double peakHours) {

		/**
		 * A line for the month: its base load's for the base's hours and its peak's for
		 * the peak's, its instances the base's with the peak's beside them.
		 * @param base the line at the base load
		 * @param peak the same part's line at the peak
		 * @return the month's line
		 */
		CostLine line(CostLine base, CostLine peak) {
			if (this.peakHours == 0) {
				return base.withPeakInstances(peak.instances());
			}
			double monthly = mixed(base.monthly(), peak.monthly());
			return new CostLine(base.id(), base.label(), base.type(), base.technology(), base.instances(),
					peak.instances(), unitPrice(base, peak, monthly), base.priceSource(), base.unit(), monthly,
					basis(base, peak), credits(base, peak), base.controlPlaneMonthly(), base.sku(), base.snapshotDate(),
					items(base, peak));
		}

		/**
		 * The cost per million successful requests of the month: both loads' requests,
		 * each for its hours.
		 * @param monthly the month's cost
		 * @param base the design priced at the base load
		 * @param peak the design priced at the peak
		 * @return the cost; {@code null} before a run, or when no request succeeded
		 */
		@Nullable Double perMillion(double monthly, PricedLoad base, PricedLoad peak) {
			if (this.peakHours == 0) {
				return LevelLoad.perMillion(monthly, base.successfulRps());
			}
			Double atBase = base.successfulRps();
			Double atPeak = peak.successfulRps();
			if (atBase == null || atPeak == null) {
				return null;
			}
			double millions = (atBase * (HOURS - this.peakHours) + atPeak * this.peakHours)
					* CostEstimator.SECONDS_PER_HOUR / CostItem.MILLION;
			return (millions > 0) ? monthly / millions : null;
		}

		// the base's figure for the base's hours and the peak's for the peak's
		private double mixed(double base, double peak) {
			return base / HOURS * (HOURS - this.peakHours) + peak / HOURS * this.peakHours;
		}

		/**
		 * How the month's line was worked out: the base's basis, with the peak's count
		 * after the base's where the two differ, or the peak's hours where only the cost
		 * does.
		 * @param base the line at the base load
		 * @param peak the line at the peak
		 * @return the basis
		 */
		private String basis(CostLine base, CostLine peak) {
			String forHours = " for " + CostText.plural(HalfUp.round(this.peakHours, 1), "hour") + " a month";
			if (peak.instances() != base.instances()) {
				int end = countEnd(base.basis());
				return base.basis().substring(0, end) + " and " + count(peak.basis()) + forHours
						+ base.basis().substring(end);
			}
			return (peak.monthly() != base.monthly()) ? base.basis() + "; at the peak" + forHours : base.basis();
		}

		/**
		 * A unit price for the month: the base's where the two loads share it (an
		 * instance's price), else the month's own, its cost over its hours or its
		 * requests.
		 * @param base the line at the base load
		 * @param peak the line at the peak
		 * @param monthly the month's cost of the line
		 * @return the unit price; {@code null} when the base has none
		 */
		private @Nullable Double unitPrice(CostLine base, CostLine peak, double monthly) {
			Double atBase = base.unitPrice();
			Double atPeak = peak.unitPrice();
			if (atBase == null || atPeak == null || atBase.equals(atPeak)) {
				return atBase;
			}
			if (base.unit() == CostUnit.HOUR) {
				return (base.instances() > 0) ? monthly / (HOURS * base.instances()) : atBase;
			}
			double requests = mixed(base.monthly() / atBase, peak.monthly() / atPeak);
			return (requests > 0) ? monthly / requests : atBase;
		}

		private @Nullable Double credits(CostLine base, CostLine peak) {
			if (base.creditsMonthly() == null && peak.creditsMonthly() == null) {
				return null;
			}
			return mixed(orZero(base.creditsMonthly()), orZero(peak.creditsMonthly()));
		}

		/**
		 * The line's priced quantities for the month, each its base's and its peak's for
		 * their hours, paired by what they count.
		 * @param base the line at the base load
		 * @param peak the line at the peak
		 * @return the items; {@code null} for a line without them
		 * @throws IllegalStateException when the two loads price other items, which both
		 * build alike
		 */
		private @Nullable List<CostItem> items(CostLine base, CostLine peak) {
			List<CostItem> atBase = base.items();
			List<CostItem> atPeak = peak.items();
			if (atBase == null && atPeak == null) {
				return null;
			}
			Map<String, CostItem> byWhat = (atPeak != null)
					? atPeak.stream()
						.collect(Collectors.toMap(CostItem::what, Function.identity(), (first, second) -> first))
					: Map.of();
			if (atBase == null || atBase.size() != byWhat.size()) {
				throw new IllegalStateException(base.id() + ": the base and the peak price other items");
			}
			List<CostItem> items = new ArrayList<>();
			for (CostItem item : atBase) {
				CostItem matched = byWhat.get(item.what());
				if (matched == null || matched.unitPrice() != item.unitPrice()) {
					throw new IllegalStateException(base.id() + ": the peak prices no " + item.what() + " as the base");
				}
				double quantity = mixed(item.quantity(), matched.quantity());
				items.add((item.per() == CostItem.MILLION)
						? CostItem.perMillion(item.what(), quantity, item.unitPrice(), item.unit())
						: CostItem.of(item.what(), quantity, item.unitPrice(), item.unit()));
			}
			return items;
		}

		// the peak's count, without its autoscaling bounds, such as 12 pods at the peak
		private static String count(String peakBasis) {
			String count = peakBasis.substring(0, countEnd(peakBasis));
			int bounds = count.indexOf(" (");
			String shown = (bounds >= 0) ? count.substring(0, bounds) : count;
			// an average left unscaled says no load
			return shown.endsWith(AT_THE_PEAK) ? shown : shown + AT_THE_PEAK;
		}

		// where a basis's leading count ends
		private static int countEnd(String basis) {
			return AFTER_THE_COUNT.stream()
				.mapToInt(basis::indexOf)
				.filter((at) -> at >= 0)
				.min()
				.orElse(basis.length());
		}

		private static double orZero(@Nullable Double value) {
			return (value != null) ? value : 0.0;
		}

	}

}
