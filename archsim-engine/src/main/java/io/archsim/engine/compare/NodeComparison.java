package io.archsim.engine.compare;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.ReportNode;

/**
 * One part of either run, side by side.
 *
 * @param id the part's id
 * @param label its name, as the first run that has it names it
 * @param type its type, such as {@code service}
 * @param statusA how it fared in the first run; {@code null} when that run does not have
 * it or does not say
 * @param statusB how it fared in the second run; {@code null} when that run does not have
 * it or does not say
 * @param a its figures in the first run; {@code null} when that run does not have it
 * @param b its figures in the second run; {@code null} when that run does not have it
 */
public record NodeComparison(String id, String label, String type, @Nullable String statusA, @Nullable String statusB,
		@Nullable NodeFigures a, @Nullable NodeFigures b) {

	/**
	 * A part side by side: named and typed as the first run that has it has it.
	 * @param id the part's id
	 * @param first the part in the first run; {@code null} when that run does not have it
	 * @param second the part in the second run; {@code null} when that run does not have
	 * it
	 * @return the part, side by side
	 * @throws NullPointerException when neither run has it
	 */
	static NodeComparison of(String id, @Nullable ReportNode first, @Nullable ReportNode second) {
		ReportNode shown = (first != null) ? first : Objects.requireNonNull(second, id);
		NodeFigures inFirst = (first != null) ? NodeFigures.of(first.summary()) : null;
		NodeFigures inSecond = (second != null) ? NodeFigures.of(second.summary()) : null;
		return new NodeComparison(id, shown.label(), shown.type(), (first != null) ? first.status() : null,
				(second != null) ? second.status() : null, inFirst, inSecond);
	}

}
