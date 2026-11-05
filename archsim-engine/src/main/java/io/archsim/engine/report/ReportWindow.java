package io.archsim.engine.report;

/**
 * The part of a run that is measured: after its warm-up, until its offered load ends.
 *
 * @param warmupS when the measured window starts, in seconds from the run's start
 * @param durationS when the offered load ends, in seconds from the run's start
 */
public record ReportWindow(double warmupS, double durationS) {

}
