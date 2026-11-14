package io.archsim.model.document;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** The hours a month a workload runs at its peak (ADR-0074). */
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

	private static Workload workload(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), Workload.class);
	}

}
