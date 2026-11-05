package io.archsim.engine.compare;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The same part of each run's report, copied whole as the report stores it, for a
 * comparison to show; what the comparison reads of it, it reads through the report's
 * records.
 *
 * @param a the part in the first run's report; {@code null} when the report does not have
 * it
 * @param b the part in the second run's report; {@code null} when the report does not
 * have it
 */
public record ReportParts(@Nullable JsonNode a, @Nullable JsonNode b) {

	/**
	 * Copies both parts, so the record does not change.
	 * @param a the part in the first run's report
	 * @param b the part in the second run's report
	 */
	public ReportParts {
		a = (a != null) ? a.deepCopy() : null;
		b = (b != null) ? b.deepCopy() : null;
	}

	/**
	 * The part in the first run's report, as a copy: a record does not change.
	 * @return a copy of the part; {@code null} when the report does not have it
	 */
	@Override
	public @Nullable JsonNode a() {
		return (this.a != null) ? this.a.deepCopy() : null;
	}

	/**
	 * The part in the second run's report, as a copy: a record does not change.
	 * @return a copy of the part; {@code null} when the report does not have it
	 */
	@Override
	public @Nullable JsonNode b() {
		return (this.b != null) ? this.b.deepCopy() : null;
	}

}
