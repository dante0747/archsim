package io.archsim.benchmarks.provenance;

/**
 * The host machine a freeze names for the benchmark designs (ADR-0095): none, a fit's
 * digest, or why the repository could not give the fit again, which keeps the engine from
 * being frozen.
 */
public sealed interface FreezeHost {

	/**
	 * No host machine: the freeze names none.
	 */
	record None() implements FreezeHost {

	}

	/**
	 * The host machine a fit's digest names.
	 *
	 * @param digest the fit's digest ({@code MachineFit.digest})
	 */
	record Fit(String digest) implements FreezeHost {

	}

	/**
	 * A host machine the repository could not give again.
	 *
	 * @param why why not, as the freeze's line says it
	 */
	record Unrepeatable(String why) implements FreezeHost {

	}

}
