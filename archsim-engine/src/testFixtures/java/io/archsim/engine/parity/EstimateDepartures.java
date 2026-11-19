package io.archsim.engine.parity;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.confidence.AnalysisConfidence;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.technology.PartTechnology;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.Architecture;
import io.archsim.model.text.PythonFormat;
import io.archsim.parity.JsonDiff;

/**
 * Where the Java's instant estimate departs from the frozen engine's, and how it is read
 * as the frozen engine's all the same, guided by the frozen engine's output of the same
 * call. Two kinds of part count their slots and CPU as a run does where Python's estimate
 * did not, and each departing part's shares are read as Python's by its own rule:
 * <ul>
 * <li>a database that flushes its commits counts its queries alone as CPU, not the log
 * flush it waits for (engine 1.5.1, ADR-0089: {@link CommitFlushDepartures});</li>
 * <li>a cache spreads its commands over every shard's cores, and a read-through cache
 * counts the command that fills a key it missed in its slots and CPU (engine 1.5.2,
 * ADR-0092: {@link CacheCommandDepartures}).</li>
 * </ul>
 * What follows from the parts' shares is read as Python's once it follows from them:
 * <ul>
 * <li>The estimate's busiest part reads as Python's once it is the one that uses most of
 * its slots or CPU, and its saturation point once it is the peak over its parts' largest
 * load, as Python's are of Python's parts. Its summary reads as Python's once it is
 * Python's with the busiest part, the overloaded part and the saturation point it names
 * replaced by the Java's own.</li>
 * <li>An analysis's confidence weighs each part by its estimated utilization. With such
 * an estimate it reads as Python's once it is what the Java's confidence makes of the
 * Java's estimate, inputs and technologies, and Python's is what it makes of
 * Python's.</li>
 * </ul>
 * Whatever else differs (a part's status, a latency, the p95 a summary gives) stays as
 * the Java wrote it, so the difference shows.
 */
public final class EstimateDepartures {

	private static final String PARTS = EstimateParts.PARTS;

	private static final String ID = EstimateParts.ID;

	private static final String UTILIZATION = EstimateParts.UTILIZATION;

	private static final String LOAD = EstimateParts.LOAD;

	private static final String LIMITED_BY = EstimateParts.LIMITED_BY;

	private static final String ESTIMATE = "estimate";

	private static final String CONFIDENCE = "confidence";

	private static final String LABEL = "label";

	private static final String BUSIEST = "busiest";

	private static final String SATURATION = "saturationRps";

	private static final String SUMMARY = "summary";

	private static final String INPUTS = "inputs";

	private static final String TECHNOLOGIES = "technologies";

	/** How a summary writes a share, as Python's format does. */
	private static final String PERCENT = ".0%";

	/**
	 * How far a saturation point, rounded to a decimal, may be from the unrounded one.
	 */
	private static final double SATURATION_ROUNDING = 0.05;

	/** How far the peak an estimate gives, rounded to two decimals, may be from it. */
	private static final double PEAK_ROUNDING = 0.005;

	/** Floating-point noise. */
	private static final double NOISE = 1e-9;

	private static final AnalysisConfidence CONFIDENCES = new AnalysisConfidence(StandardComponents.registry());

	/**
	 * A design without links: an analysis does not hold its design, so a driver that is a
	 * link would be named by its input and show as a difference. No recording has one.
	 */
	private static final Architecture NO_LINKS = new Architecture(List.of(), List.of(), true, null);

	private EstimateDepartures() {
	}

	/**
	 * The Java's output with its estimate of the parts that depart, and what follows from
	 * it, read as the frozen engine's.
	 * @param java the Java's output, an estimate or what holds one as its
	 * {@code estimate}; it is not changed
	 * @param python the frozen engine's output of the same call
	 * @return a copy of the Java's output
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python) {
		JsonNode copy = java.deepCopy();
		if (isEstimate(copy) && isEstimate(python)) {
			putEstimate((ObjectNode) copy, python);
		}
		else if (isEstimate(copy.path(ESTIMATE)) && isEstimate(python.path(ESTIMATE))) {
			JsonNode own = copy.get(ESTIMATE).deepCopy();
			if (putEstimate((ObjectNode) copy.get(ESTIMATE), python.get(ESTIMATE))) {
				putConfidence((ObjectNode) copy, own, python);
			}
		}
		return copy;
	}

	private static boolean isEstimate(JsonNode json) {
		return json.path(PARTS).isArray() && json.has(SATURATION);
	}

	// reads the parts that depart, then the figures that follow from the parts' shares;
	// whether any part departs
	private static boolean putEstimate(ObjectNode java, JsonNode python) {
		JsonNode own = java.deepCopy();
		JsonNode parts = java.get(PARTS);
		boolean departs = false;
		for (int index = 0; index < Math.min(parts.size(), python.get(PARTS).size()); index++) {
			departs |= putPart((ObjectNode) parts.get(index), python.get(PARTS).get(index));
		}
		if (departs) {
			putTotals(java, own, python);
		}
		return departs;
	}

	private static boolean putPart(ObjectNode java, JsonNode python) {
		return CommitFlushDepartures.putDatabase(java, python) || CacheCommandDepartures.putCache(java, python);
	}

	// each read as Python's once it follows from the Java's own parts as Python's does
	// from Python's
	private static void putTotals(ObjectNode java, JsonNode own, JsonNode python) {
		if (namesItsBusiest(own) && namesItsBusiest(python)) {
			java.set(BUSIEST, python.get(BUSIEST));
		}
		if (saturatesAtItsLargestLoad(own) && saturatesAtItsLargestLoad(python)) {
			java.set(SATURATION, python.get(SATURATION));
		}
		if (summaryFollows(own, python)) {
			java.set(SUMMARY, python.get(SUMMARY));
		}
	}

	// its busiest part: a part with the largest utilization, under its name and limit
	private static boolean namesItsBusiest(JsonNode estimate) {
		JsonNode busiest = estimate.path(BUSIEST);
		double largest = largest(estimate, UTILIZATION);
		return busiest.path(UTILIZATION).isNumber() && busiest.path(UTILIZATION).doubleValue() == largest
				&& estimate.path(PARTS).valueStream().anyMatch((part) -> isPart(busiest, part, largest));
	}

	private static boolean isPart(JsonNode busiest, JsonNode part, double utilization) {
		boolean named = part.path(ID).equals(busiest.path(ID)) && part.path(LABEL).equals(busiest.path(LABEL));
		return named && part.path(LIMITED_BY).equals(busiest.path(LIMITED_BY))
				&& EstimateParts.number(part.path(UTILIZATION)) == utilization;
	}

	// its saturation point: the peak over its parts' largest load, none when none is
	// loaded
	private static boolean saturatesAtItsLargestLoad(JsonNode estimate) {
		double load = largest(estimate, LOAD);
		JsonNode saturation = estimate.path(SATURATION);
		if (load <= 0) {
			return saturation.isNull();
		}
		double expected = EstimateParts.number(estimate.path("atRps")) / load;
		return saturation.isNumber() && Math.abs(saturation.doubleValue() - expected) <= SATURATION_ROUNDING
				+ PEAK_ROUNDING / load + NOISE * expected;
	}

	private static double largest(JsonNode estimate, String share) {
		return estimate.path(PARTS)
			.valueStream()
			.mapToDouble((part) -> EstimateParts.number(part.path(share)))
			.max()
			.orElse(0.0);
	}

	// the Java's summary is Python's with what the departure moves put in the Java's own
	// words: the busiest part, the overloaded part and the saturation point
	private static boolean summaryFollows(JsonNode own, JsonNode python) {
		String expected = python.path(SUMMARY).stringValue("");
		expected = replaced(expected, busiestClause(python), busiestClause(own));
		expected = replaced(expected, overloadedClause(python), overloadedClause(own));
		expected = replaced(expected, saturationClause(python), saturationClause(own));
		return expected.equals(own.path(SUMMARY).stringValue(""));
	}

	// a summary with Python's clause replaced by the Java's, where both estimates have
	// the figure it gives
	private static String replaced(String summary, Optional<String> theirs, Optional<String> ours) {
		return (theirs.isPresent() && ours.isPresent()) ? summary.replace(theirs.get(), ours.get()) : summary;
	}

	// how a summary names the busiest part, as the engine words it
	private static Optional<String> busiestClause(JsonNode estimate) {
		JsonNode busiest = estimate.path(BUSIEST);
		if (!busiest.isObject()) {
			return Optional.empty();
		}
		return Optional.of("busiest " + busiest.path(LABEL).stringValue("") + " "
				+ PythonFormat.format(EstimateParts.number(busiest.path(UTILIZATION)), PERCENT));
	}

	// how a summary names the overloaded part that needs most of its capacity: the one
	// with the largest load, the first of equal ones
	private static Optional<String> overloadedClause(JsonNode estimate) {
		return estimate.path(PARTS)
			.valueStream()
			.filter((part) -> "overloaded".equals(part.path("status").stringValue("")))
			.reduce((worst, part) -> (EstimateParts.number(part.path(LOAD)) > EstimateParts.number(worst.path(LOAD)))
					? part : worst)
			.map((worst) -> worst.path(LABEL).stringValue("") + " needs "
					+ PythonFormat.format(EstimateParts.number(worst.path(LOAD)), PERCENT) + " of its capacity");
	}

	// how a summary gives the saturation point
	private static Optional<String> saturationClause(JsonNode estimate) {
		JsonNode saturation = estimate.path(SATURATION);
		if (!saturation.isNumber()) {
			return Optional.empty();
		}
		return Optional.of("about " + PythonFormat.format(saturation.doubleValue(), ",.0f") + " req/s");
	}

	private static void putConfidence(ObjectNode analysis, JsonNode ownEstimate, JsonNode python) {
		if (!isAnalysis(analysis) || !isAnalysis(python)) {
			return;
		}
		JsonNode theirs = python.get(CONFIDENCE);
		boolean oursFollows = isWritten(confidence(ownEstimate, analysis), analysis.get(CONFIDENCE));
		if (oursFollows && isWritten(confidence(python.get(ESTIMATE), python), theirs)) {
			analysis.set(CONFIDENCE, theirs);
		}
	}

	private static boolean isAnalysis(JsonNode json) {
		return json.path(CONFIDENCE).isObject() && json.path(INPUTS).isArray() && json.path(TECHNOLOGIES).isArray();
	}

	// what the Java's confidence makes of an estimate and its analysis's inputs and
	// technologies
	private static JsonNode confidence(JsonNode estimate, JsonNode analysis) {
		List<Input> inputs = analysis.get(INPUTS)
			.valueStream()
			.map((input) -> RecordCodec.read(input, Input.class))
			.toList();
		List<PartTechnology> technologies = analysis.get(TECHNOLOGIES)
			.valueStream()
			.map((technology) -> RecordCodec.read(technology, PartTechnology.class))
			.toList();
		return RecordCodec.write(CONFIDENCES.of(EstimateJson.read(estimate), inputs, technologies, NO_LINKS));
	}

	private static boolean isWritten(JsonNode made, JsonNode written) {
		return JsonDiff.differences(written, made).isEmpty();
	}

}
