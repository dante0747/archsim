package io.archsim.engine.cost;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.text.NumberText;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.Sku;

/**
 * A part priced per instance-hour: its quote times its instances, with a burstable type's
 * surplus CPU credits and a managed cluster's control plane.
 */
final class QuoteLines {

	private static final String GATEWAY_NOTE = "an API gateway is billed per request, which the price snapshot does "
			+ "not cover";

	private QuoteLines() {
	}

	/**
	 * A part's line.
	 * @param part the part
	 * @param quote its price per instance-hour
	 * @param count its billable instances
	 * @param context the snapshot and the part's CPU at the load
	 * @return the line
	 */
	static CostLine of(CostPart part, Quote quote, InstanceCount count, Context context) {
		Credits credits = (quote.basis() == PriceBasis.SNAPSHOT)
				? Credits.of(sku(context.snapshot(), quote.sku()), context.busy(), count.count()) : null;
		String note = note(part, quote, credits, context.snapshot());
		double monthly = hourly(quote, count) + ((credits != null) ? credits.monthly() : 0.0);
		String basis = count.basis() + ((note != null) ? "; " + note : "");
		Double plane = controlPlane(part, quote, context.snapshot());
		Double planeMonthly = null;
		if (plane != null) {
			// a managed cluster also pays for its control plane
			planeMonthly = plane * CostEstimator.HOURS_PER_MONTH;
			monthly += planeMonthly;
			basis += "; + its managed control plane at $" + NumberText.plain(plane) + "/h";
		}
		return new CostLine(part.id(), part.label(), part.type(), part.technology(), count.count(), null, quote.price(),
				quote.basis(), CostUnit.HOUR, monthly, basis, surplus(credits), planeMonthly, quote.sku(),
				(quote.sku() != null) ? context.snapshot().snapshotDate() : null, null);
	}

	/**
	 * The quote's note, an API gateway's that the snapshot does not price, and the
	 * credits' note.
	 * @param part the part
	 * @param quote its quote
	 * @param credits its surplus CPU credits; {@code null} for none
	 * @param snapshot the price snapshot
	 * @return the note; {@code null} for none
	 */
	private static @Nullable String note(CostPart part, Quote quote, @Nullable Credits credits,
			PriceSnapshot snapshot) {
		String note = quote.note();
		if (part.technologyIn(ManagedLines.PER_REQUEST) && !snapshot.illustrative()
				&& quote.basis() != PriceBasis.SET) {
			note = joined(note, GATEWAY_NOTE);
		}
		return (credits != null) ? joined(note, credits.note()) : note;
	}

	private static String joined(@Nullable String note, String more) {
		return (note != null) ? note + "; " + more : more;
	}

	private static double hourly(Quote quote, InstanceCount count) {
		Double price = quote.price();
		return (price != null) ? price * count.count() * CostEstimator.HOURS_PER_MONTH : 0.0;
	}

	/**
	 * A managed cluster's control plane, when the snapshot prices the cluster and has a
	 * rate for it.
	 * @param part the part
	 * @param quote its quote
	 * @param snapshot the price snapshot
	 * @return the control plane's price per hour; {@code null} for none
	 */
	private static @Nullable Double controlPlane(CostPart part, Quote quote, PriceSnapshot snapshot) {
		Double plane = snapshot.rates().get("kubernetesControlPlaneHour");
		boolean managed = KubernetesClusterSpec.TYPE.equals(part.type()) && quote.basis() == PriceBasis.SNAPSHOT;
		return (managed && plane != null && plane != 0) ? plane : null;
	}

	private static @Nullable Double surplus(@Nullable Credits credits) {
		return (credits != null && credits.monthly() > 0) ? credits.monthly() : null;
	}

	/**
	 * The snapshot's instance type of a name, in any of its price lists.
	 * @param snapshot the snapshot
	 * @param name the type's name; {@code null} for none
	 * @return the type; {@code null} when there is none of that name
	 */
	private static @Nullable Sku sku(PriceSnapshot snapshot, @Nullable String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		return snapshot.skus()
			.values()
			.stream()
			.flatMap((skus) -> skus.stream())
			.filter((sku) -> sku.sku().equals(name))
			.findFirst()
			.orElse(null);
	}

	/**
	 * What a line is priced with.
	 *
	 * @param snapshot the price snapshot
	 * @param busy the CPU cores one instance keeps busy at the load; {@code null} when
	 * unknown
	 */
	record Context(PriceSnapshot snapshot, @Nullable Double busy) {
	}

}
