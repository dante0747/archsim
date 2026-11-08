package io.archsim.engine.random;

import io.archsim.model.distribution.Constant;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Empirical;
import io.archsim.model.distribution.Exponential;
import io.archsim.model.distribution.LogNormal;
import io.archsim.model.distribution.Uniform;

/**
 * The sampler of each kind of distribution, as {@code distributions.py} samples it.
 */
final class Samplers {

	private Samplers() {
	}

	static Sampler of(Distribution distribution) {
		return switch (distribution) {
			case Constant constant -> (_) -> constant.value();
			case Exponential exponential -> exponential(1.0 / exponential.mean());
			case Uniform uniform -> (random) -> random.uniform(uniform.min(), uniform.max());
			case LogNormal logNormal -> logNormal(logNormal);
			case Empirical empirical ->
				(random) -> Math.min(empirical.quantile(random.nextDouble()), Distribution.MAX_SAMPLE_MS);
		};
	}

	private static Sampler exponential(double rate) {
		return (random) -> Math.min(random.exponential(rate), Distribution.MAX_SAMPLE_MS);
	}

	private static Sampler logNormal(LogNormal distribution) {
		double sigma = distribution.sigma();
		if (sigma == 0) {
			return (_) -> distribution.median();
		}
		double mu = distribution.mu();
		return (random) -> Math.min(random.logNormal(mu, sigma), Distribution.MAX_SAMPLE_MS);
	}

}
