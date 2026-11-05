package io.archsim.engine.confidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.provenance.InputKind;
import io.archsim.engine.provenance.InputProvenances;
import io.archsim.engine.technology.PartTechnology;
import io.archsim.model.component.Tier;
import io.archsim.model.text.PythonFormat;

/**
 * One result's confidence being worked out from its evidence ({@code assess}): each
 * part's weight and how much of it rests on grounded inputs decide the level; the facts
 * behind it, the inputs to replace first and how to raise it follow.
 */
final class ConfidenceAssessment {

	/** Largest calibration fit error at which a measured input counts as grounded. */
	private static final double MEASURED_FIT = 0.15;

	/** A calibration fit error above this makes confidence low. */
	private static final double BAD_FIT = 0.3;

	/** The grounded share of the weight high confidence needs. */
	private static final double HIGH_SHARE = 0.8;

	/** Under this grounded share of the weight, confidence is low. */
	private static final double LOW_SHARE = 0.4;

	/** The most inputs listed to replace first. */
	private static final int MOST_DRIVERS = 6;

	/** The parts named in a reason, at most. */
	private static final int MOST_NAMED = 3;

	/** The bad fits listed among the further reasons, after the first. */
	private static final int MORE_BAD_FITS = 2;

	private static final int WEIGHT_DIGITS = 3;

	/**
	 * The inputs that move latency and capacity most, listed first among the drivers. A
	 * CPU share sets how much CPU a request needs, so it moves CPU capacity as a service
	 * time does.
	 */
	private static final Set<String> PRIMARY = Set.of("serviceTimeMs", "readServiceTimeMs", "writeServiceTimeMs",
			"latencyMs", "overheadMs", "hitRatio", "ttlSeconds", "publishTimeMs", "consumerTimeMs", "commitFlushMs",
			"cpuShare");

	private final Evidence evidence;

	private final Map<PartKey, String> labels;

	private final Map<PartKey, List<Input>> byPart = new LinkedHashMap<>();

	private final Map<PartKey, Double> weight;

	private final WeightedBy basis;

	private final double total;

	private final Map<PartKey, Double> covered = new LinkedHashMap<>();

	private final double share;

	private final long groundedCount;

	private final List<Input> badFits;

	/** The experimental technology at the bottleneck; {@code null} for none. */
	private final @Nullable PartTechnology atBottleneck;

	private final List<PartTechnology> onPathExperimental;

	private final List<PartKey> heavy;

	private final String shareText;

	private final String ungroundedText;

	ConfidenceAssessment(Evidence evidence) {
		this.evidence = evidence;
		this.labels = new LinkedHashMap<>(evidence.labels());
		evidence.inputs().forEach(this::fileByPart);
		Map<PartKey, Double> weighted = resultWeights();
		this.basis = weighted.isEmpty() ? WeightedBy.INPUTS : evidence.weightedBy();
		this.weight = weighted.isEmpty() ? evenWeights() : weighted;
		this.total = FloatSum.of(this.weight.values().stream().mapToDouble(Double::doubleValue));
		this.weight.keySet().forEach((key) -> this.covered.put(key, groundedShare(inputsOf(key))));
		this.share = (this.total > 0)
				? FloatSum.of(this.weight.keySet().stream().mapToDouble((key) -> weightOf(key) * coveredOf(key)))
						/ this.total
				: 0.0;
		this.groundedCount = evidence.inputs().stream().filter(ConfidenceAssessment::grounded).count();
		this.badFits = badFits(evidence.inputs());
		this.atBottleneck = experimental().stream()
			.filter((part) -> part.id().equals(evidence.bottleneck()))
			.findFirst()
			.orElse(null);
		this.onPathExperimental = onPathExperimental();
		this.heavy = heavy();
		this.shareText = PythonFormat.format(this.share, ".0%") + " " + this.basis.ofWhat()
				+ " rests on measured or documented inputs";
		this.ungroundedText = ungroundedText();
	}

	/**
	 * The result's own weights of the parts with inputs.
	 * @return each part's weight, in the result's order; empty when no part with inputs
	 * has one
	 */
	private Map<PartKey, Double> resultWeights() {
		Map<PartKey, Double> weighted = new LinkedHashMap<>();
		this.evidence.weights().forEach((key, value) -> {
			if (this.byPart.containsKey(key) && value > 0) {
				weighted.put(key, value);
			}
		});
		return weighted;
	}

	/**
	 * The same weight for each part with inputs, when the result weighs none.
	 * @return a weight of one for each part, in the inputs' order
	 */
	private Map<PartKey, Double> evenWeights() {
		Map<PartKey, Double> even = new LinkedHashMap<>();
		this.byPart.keySet().forEach((key) -> even.put(key, 1.0));
		return even;
	}

	private List<PartTechnology> onPathExperimental() {
		Set<String> onPath = this.evidence.onPath();
		return experimental().stream().filter((part) -> onPath == null || onPath.contains(part.id())).toList();
	}

	/**
	 * The parts whose inputs are not all grounded, those with the most ungrounded weight
	 * first.
	 * @return the parts
	 */
	private List<PartKey> heavy() {
		return this.weight.keySet()
			.stream()
			.filter((key) -> coveredOf(key) < 1)
			.sorted(Comparator.comparingDouble((PartKey key) -> weightOf(key) * (1 - coveredOf(key))).reversed())
			.toList();
	}

	/**
	 * The measured inputs whose calibration fits badly, the worst first.
	 * @param inputs the inputs
	 * @return the bad fits
	 */
	private static List<Input> badFits(List<Input> inputs) {
		return inputs.stream()
			.filter((input) -> input.provenance().kind() == InputKind.MEASURED && fit(input) > BAD_FIT)
			.sorted(Comparator.comparingDouble(ConfidenceAssessment::fit).reversed())
			.toList();
	}

	/**
	 * The result's confidence.
	 * @return the level, the reasons for it, the inputs to replace first and how to raise
	 * it
	 */
	Confidence confidence() {
		Verdict verdict = verdict();
		List<String> reasons = reasons(verdict);
		return new Confidence(verdict.level(), reasons.get(0), reasons, drivers(), this.basis,
				howToRaise(verdict.level()));
	}

	private void fileByPart(Input input) {
		PartKey.of(input).ifPresent((key) -> {
			this.labels.putIfAbsent(key, input.label().isEmpty() ? key.id() : input.label());
			if (!input.field().startsWith("cost")) {
				this.byPart.computeIfAbsent(key, (part) -> new ArrayList<>()).add(input);
			}
		});
	}

	/**
	 * The level and the reason that decided it: a bad calibration fit, an experimental
	 * technology at the bottleneck, else the grounded share of the weight.
	 * @return the verdict
	 */
	private Verdict verdict() {
		if (!this.badFits.isEmpty()) {
			Input input = this.badFits.get(0);
			return new Verdict(ConfidenceLevel.LOW, badFit(input) + " (above " + percent(BAD_FIT) + ")");
		}
		PartTechnology part = this.atBottleneck;
		if (part != null) {
			return new Verdict(ConfidenceLevel.LOW,
					"The " + this.evidence.bottleneckWord() + ", " + part.label()
							+ ", uses an experimental technology (" + part.technology()
							+ ") whose model is not yet validated against measurements");
		}
		return verdictByShare();
	}

	private Verdict verdictByShare() {
		int inputs = this.evidence.inputs().size();
		long ungrounded = inputs - this.groundedCount;
		if (this.share < LOW_SHARE) {
			return new Verdict(ConfidenceLevel.LOW, (ungrounded >= inputs / 2.0) ? this.ungroundedText
					: "Only " + this.shareText + ": " + names() + " rest on assumed or illustrative inputs");
		}
		if (this.share >= HIGH_SHARE && this.onPathExperimental.isEmpty()) {
			return new Verdict(ConfidenceLevel.HIGH,
					this.groundedCount + " of " + inputs + " inputs are measured or documented");
		}
		if (this.share >= HIGH_SHARE) {
			PartTechnology part = this.onPathExperimental.get(0);
			return new Verdict(ConfidenceLevel.MEDIUM,
					part.label() + " on the request path uses an experimental technology (" + part.technology() + ")");
		}
		return new Verdict(ConfidenceLevel.MEDIUM, this.shareText + "; high confidence needs " + percent(HIGH_SHARE));
	}

	/**
	 * The reasons: the one that decided the level, then the other facts behind it.
	 * @param verdict the level and its reason
	 * @return the reasons, each once
	 */
	private List<String> reasons(Verdict verdict) {
		String first = verdict.reason();
		List<String> extra = new ArrayList<>();
		if (!first.contains(this.shareText)) {
			extra.add(this.shareText);
		}
		if (verdict.level() != ConfidenceLevel.HIGH && this.groundedCount < this.evidence.inputs().size()
				&& !this.ungroundedText.equals(first)) {
			extra.add(this.ungroundedText);
		}
		this.badFits.stream().skip(1).limit(MORE_BAD_FITS).forEach((input) -> extra.add(badFit(input)));
		this.onPathExperimental.stream()
			.filter((part) -> !first.contains(part.label()))
			.forEach(
					(part) -> extra.add(part.label() + " uses an experimental technology (" + part.technology() + ")"));
		// each extra reason is checked against the first only, as in Python
		List<String> reasons = new ArrayList<>(List.of(first));
		extra.stream().filter((reason) -> !reason.equals(first)).forEach(reasons::add);
		return List.copyOf(reasons);
	}

	/**
	 * The inputs to replace first: the bad fits, then the ungrounded inputs of the
	 * heaviest parts, those that move latency and capacity most first. A part's inputs
	 * are checked against those picked before the part, as in Python, so two equal inputs
	 * of one part are both picked.
	 * @return up to six inputs
	 */
	private List<Driver> drivers() {
		List<Input> picked = new ArrayList<>(this.badFits);
		for (boolean primary : new boolean[] { true, false }) {
			for (PartKey key : this.heavy) {
				List<Input> more = inputsOf(key).stream()
					.filter((input) -> !grounded(input) && PRIMARY.contains(input.field()) == primary
							&& !picked.contains(input))
					.toList();
				picked.addAll(more);
			}
		}
		return picked.stream().limit(MOST_DRIVERS).map(this::driver).toList();
	}

	private Driver driver(Input input) {
		Optional<PartKey> key = PartKey.of(input);
		String label = key.map((part) -> this.labels.getOrDefault(part, input.label())).orElse(input.label());
		Double weightShare = (key.isPresent() && this.total > 0)
				? PythonRound.round(this.weight.getOrDefault(key.get(), 0.0) / this.total, WEIGHT_DIGITS) : null;
		return new Driver(input.scope(), input.id(), label, input.field(), input.fieldLabel(),
				input.provenance().kind(), weightShare);
	}

	private String howToRaise(ConfidenceLevel level) {
		if (!this.badFits.isEmpty()) {
			return "Calibrate " + this.badFits.get(0).label() + " again over a steadier time range, or check that the "
					+ "part is drawn the way it runs.";
		}
		PartTechnology part = this.atBottleneck;
		if (part != null) {
			return "Measure " + part.label() + " and calibrate it from telemetry, or use a core technology there.";
		}
		if (level == ConfidenceLevel.HIGH) {
			return "Keep the calibration current: calibrate again after the system changes.";
		}
		String names = names();
		return "Calibrate from telemetry, or replace the illustrative and assumed inputs of "
				+ (names.isEmpty() ? "the parts" : names) + " with measured values.";
	}

	private String ungroundedText() {
		int inputs = this.evidence.inputs().size();
		int illustrative = InputProvenances.counts(this.evidence.inputs()).getOrDefault(InputKind.ILLUSTRATIVE, 0);
		if (inputs > 0 && illustrative >= inputs - this.groundedCount - illustrative) {
			return illustrative + " of " + inputs + " inputs are illustrative defaults";
		}
		return (inputs - this.groundedCount) + " of " + inputs + " inputs are assumed or illustrative, not measured";
	}

	/**
	 * The heaviest ungrounded parts' names, as a reason lists them.
	 * @return up to three names, then how many more
	 */
	private String names() {
		List<String> names = this.heavy.stream()
			.limit(MOST_NAMED)
			.map((key) -> this.labels.getOrDefault(key, key.id()))
			.toList();
		if (names.size() <= 2) {
			return String.join(" and ", names);
		}
		return String.join(", ", names.subList(0, 2)) + " and " + (names.size() - 2) + " more";
	}

	private String badFit(Input input) {
		return "The calibration of " + input.label() + " " + input.fieldLabel().toLowerCase(Locale.ROOT)
				+ " fits only within " + percent(fit(input));
	}

	private List<PartTechnology> experimental() {
		return this.evidence.technologies().stream().filter((part) -> part.tier() == Tier.EXPERIMENTAL).toList();
	}

	private List<Input> inputsOf(PartKey key) {
		return Objects.requireNonNull(this.byPart.get(key), key.id());
	}

	private double weightOf(PartKey key) {
		return Objects.requireNonNull(this.weight.get(key), key.id());
	}

	private double coveredOf(PartKey key) {
		return Objects.requireNonNull(this.covered.get(key), key.id());
	}

	/**
	 * Whether an input rests on something checked: measured with a calibration fit error
	 * of at most 15% (or none recorded), or documented.
	 * @param input the input
	 * @return whether it is grounded
	 */
	static boolean grounded(Input input) {
		Double fit = input.provenance().fitError();
		return switch (input.provenance().kind()) {
			case DOCUMENTED -> true;
			case MEASURED -> fit == null || fit <= MEASURED_FIT;
			default -> false;
		};
	}

	private static double groundedShare(List<Input> inputs) {
		return (double) inputs.stream().filter(ConfidenceAssessment::grounded).count() / inputs.size();
	}

	private static double fit(Input input) {
		Double fit = input.provenance().fitError();
		return (fit != null) ? fit : 0.0;
	}

	private static String percent(double fraction) {
		return PythonFormat.format(fraction, ".0%");
	}

	/**
	 * A level and the reason that decided it.
	 *
	 * @param level the level
	 * @param reason why
	 */
	private record Verdict(ConfidenceLevel level, String reason) {
	}

}
