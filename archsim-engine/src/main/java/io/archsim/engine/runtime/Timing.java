package io.archsim.engine.runtime;

import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;

/**
 * Draws a part's time from a distribution, stretched by a slowdown planned for the part
 * now: a part's {@code sampleTime}, for the classes that work on its behalf.
 */
@FunctionalInterface
public interface Timing {

	/**
	 * Draws a time.
	 * @param sampler the distribution's sampler
	 * @param stream the stream to draw from
	 * @return milliseconds
	 */
	double sample(Sampler sampler, PythonRandom stream);

}
