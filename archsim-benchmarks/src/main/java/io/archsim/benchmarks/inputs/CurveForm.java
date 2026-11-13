package io.archsim.benchmarks.inputs;

/**
 * How the service's CPU per request continues above the calibration staircase's top rate
 * (run.py's {@code CURVE_FORM}).
 *
 * <p>
 * The derivation keeps it {@link #FLAT} until a protocol v6 campaign shows the log form
 * helps: it was checked only on staircases built from the kept runs' ladders
 * ({@code docs/validation.md}, Protocol), and on those it helped cache-aside, left
 * pg-mixed alone but raised Kafka's knee, which is not the CPU's.
 *
 * <p>
 * In run.py the log form was one constant away. Here the derivation
 * ({@code LoadCurveInput.of}) passes neither the staircase's passes nor the service's
 * CPUs, since the flat form uses neither. Trying the log form, as
 * {@code docs/validation.md} plans ({@code CURVE_FORM = "log"}, then {@code resimulate}),
 * means calling {@code LoadCurveInput.curve} from {@code ServiceInputs} with
 * {@code LoadCurveInput.staircaseCpu(passes.staircase, passes.idle)} and the service's
 * CPUs. run.py counted those from today's {@code compose.yaml} cpusets. For a run derived
 * again, the cpusets its own {@code environment.json} recorded are the faithful choice.
 */
enum CurveForm {

	/** The top rate's cost: engine 0.15.0's rule, and the derivation's. */
	FLAT,

	/**
	 * W26's extension: the upper staircase's fall carried on linearly in log(rate), at
	 * most one measured stretch, up to the service's CPUs' capacity.
	 */
	LOG

}
