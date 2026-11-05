package io.archsim.engine.confidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.provenance.Input;
import io.archsim.engine.technology.PartTechnology;

/**
 * What a result's confidence is assessed from.
 *
 * @param inputs each input of the design, with where its value came from
 * @param weights each part's or link's weight in the result, in the order weighed
 * @param weightedBy what the weights are
 * @param technologies each part's technology, by its name, and its tier
 * @param bottleneck the part that limits the result; {@code null} for none
 * @param onPath the parts on the request path; {@code null} when every part counts as on
 * it
 * @param labels the names of links (and parts), where their inputs do not name them
 * @param bottleneckWord what the result calls its bottleneck, such as
 * {@code busiest part}
 */
public record Evidence(List<Input> inputs, Map<PartKey, Double> weights, WeightedBy weightedBy,
		List<PartTechnology> technologies, @Nullable String bottleneck, @Nullable Set<String> onPath,
		Map<PartKey, String> labels, String bottleneckWord) {

	/**
	 * Copies the collections in their order, so the record does not change.
	 */
	public Evidence {
		inputs = List.copyOf(inputs);
		weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
		technologies = List.copyOf(technologies);
		onPath = (onPath != null) ? Collections.unmodifiableSet(new LinkedHashSet<>(onPath)) : null;
		labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
	}

	/**
	 * The confidence the evidence supports ({@code assess}).
	 * @return the level, its reasons, the inputs to replace first and how to raise it
	 */
	public Confidence assess() {
		return new ConfidenceAssessment(this).confidence();
	}

}
