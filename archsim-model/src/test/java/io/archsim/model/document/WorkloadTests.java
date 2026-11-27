package io.archsim.model.document;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The hours a month a workload runs at its peak (ADR-0074), and the checks that span its
 * fields, which hold for any number, NaN included: a record can be made without the
 * codec, and a comparison with NaN is false whichever way it asks.
 */
class WorkloadTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void read_withoutPeakHours_runsNoHourAtThePeak() {
		assertThat(workload("{\"rps\": 300}").peakHoursPerMonth()).isZero();
	}

	@Test
	void write_noPeakHours_leavesTheSettingOut() {
		assertThat(RecordCodec.write(workload("{\"rps\": 300}")).has("peakHoursPerMonth")).isFalse();
	}

	@Test
	void write_somePeakHours_keepsThem() {
		assertThat(RecordCodec.write(workload("{\"rps\": 300, \"peakHoursPerMonth\": 24}"))
			.path("peakHoursPerMonth")
			.doubleValue()).isEqualTo(24.0);
	}

	@Test
	void read_everyHourOfTheMonth_isTaken() {
		assertThat(workload("{\"peakHoursPerMonth\": 730}").peakHoursPerMonth()).isEqualTo(730.0);
	}

	@Test
	void read_negativeHours_areRefused() {
		assertThatExceptionOfType(BindingException.class).isThrownBy(() -> workload("{\"peakHoursPerMonth\": -1}"));
	}

	@Test
	void read_moreHoursThanAMonthHas_isRefused() {
		assertThatExceptionOfType(BindingException.class).isThrownBy(() -> workload("{\"peakHoursPerMonth\": 731}"));
	}

	@Test
	void new_aWarmUpThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> workload(60, Double.NaN, new ConstantPattern()))
			.withMessage("warmupS must be shorter than durationS");
	}

	@Test
	void new_aDurationThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> workload(Double.NaN, 10, new ConstantPattern()))
			.withMessage("warmupS must be shorter than durationS");
	}

	@Test
	void new_aBurstThatStartsAtATimeThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> workload(60, 10, new BurstPattern(500, Double.NaN, 5)))
			.withMessage("burst startS must be within durationS");
	}

	private static Workload workload(double durationS, double warmupS, TrafficPattern pattern) {
		return new Workload(100, durationS, warmupS, 42, 0.9, ArrivalProcess.POISSON, pattern, 1_000, 0.99, null,
				List.of(), 0);
	}

	private static Workload workload(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), Workload.class);
	}

}
