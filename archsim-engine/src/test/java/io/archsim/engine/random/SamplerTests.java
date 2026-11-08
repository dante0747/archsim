package io.archsim.engine.random;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.archsim.model.distribution.Constant;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Exponential;
import io.archsim.model.distribution.LogNormal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The samplers' behaviour beyond the recorded draws ({@link RandomCasesTests}): what they
 * leave of the stream, and the cap on long tails.
 */
class SamplerTests {

	@Test
	void sample_constant_drawsNothing() {
		PythonRandom sampled = new RandomStreams(7).stream("node:svc:service");
		PythonRandom untouched = new RandomStreams(7).stream("node:svc:service");

		Sampler.of(new Constant(5)).sample(sampled);

		assertThat(sampled.nextDouble()).isEqualTo(untouched.nextDouble());
	}

	@Test
	void sample_logNormalWithoutSpread_isItsMedian() {
		PythonRandom random = new RandomStreams(7).stream("node:svc:service");

		double value = Sampler.of(new LogNormal(8, 8)).sample(random);

		assertThat(value).isEqualTo(8);
	}

	@Test
	void sample_logNormalWithoutSpread_drawsNothing() {
		PythonRandom sampled = new RandomStreams(7).stream("node:svc:service");
		PythonRandom untouched = new RandomStreams(7).stream("node:svc:service");

		Sampler.of(new LogNormal(8, 8)).sample(sampled);

		assertThat(sampled.nextDouble()).isEqualTo(untouched.nextDouble());
	}

	@Test
	void sample_exponentialPastTenMinutes_isCapped() {
		PythonRandom random = new RandomStreams(1).stream("arrivals");
		Sampler sampler = Sampler.of(new Exponential(Distribution.MAX_SAMPLE_MS));

		double longest = Stream.generate(() -> sampler.sample(random))
			.limit(200)
			.mapToDouble(Double::doubleValue)
			.max()
			.orElseThrow();

		assertThat(longest).isEqualTo(Distribution.MAX_SAMPLE_MS);
	}

}
