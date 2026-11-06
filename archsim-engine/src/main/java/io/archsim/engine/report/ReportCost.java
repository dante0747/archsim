package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

/**
 * A run's monthly cost, as far as the engine reads it of the run's report.
 *
 * @param monthly the monthly cost; {@code null} when the report does not say
 * @param hasCost whether any part has a price; {@code null} when the report does not say
 */
public record ReportCost(@Nullable Double monthly, @Nullable Boolean hasCost) {

}
