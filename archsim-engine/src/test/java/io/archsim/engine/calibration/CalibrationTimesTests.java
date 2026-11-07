package io.archsim.engine.calibration;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Times read as the Python engine's {@code parse_time} reads them, through Python 3.12's
 * {@code datetime.fromisoformat}, and written as its {@code _iso} writes them. Expected
 * values are Python's.
 */
class CalibrationTimesTests {

	@ParameterizedTest
	@ValueSource(strings = { "2026-10-01T00:00:00Z", "2026-10-01T00:00:00", "2026-10-01T02:00:00+02:00", "2026-10-01",
			"20261001T000000Z", "2026-10-01T00Z", "2026-W40-4T00:00:00Z", "2026W404T00:00:00Z", "2026-10-01x00:00:00Z",
			"2026-10-01é00:00", "2026-10-01 00:00:00 Z", " 2026-10-01 ", "2026-10-01T00:00+00:00:00.5" })
	void parse_isoTime_isItsSecondsWithNoOffsetAsUtc(String time) {
		assertThat(CalibrationTimes.parse(time)).isEqualTo(1790812800.0);
	}

	@ParameterizedTest
	@MethodSource("times")
	void parse_formsPythonsFromIsoFormatReads_areTheSecondsPythonGives(String time, double seconds) {
		assertThat(CalibrationTimes.parse(time)).isEqualTo(seconds);
	}

	/** Each with what the Python engine's parse_time gives. */
	static Stream<Arguments> times() {
		return Stream.of(Arguments.of("2026-10-01 00:00:30.5Z", 1790812830.5),
				Arguments.of("2026-10-01T00:00:00+0200", 1790805600.0), Arguments.of("2026W40", 1790553600.0),
				Arguments.of("2020-W53-1", 1609113600.0), Arguments.of("2026-10-01T00:00:00,5Z", 1790812800.5),
				Arguments.of("2026-10-01T1230", 1790857800.0), Arguments.of("2026-10-01T12.5", 1790856000.5),
				Arguments.of("2026-10-01T12:30:45:12", 1790857845.12),
				Arguments.of("2026-10-01T12:30:45.123456789", 1790857845.123456),
				Arguments.of("2026-10-01T00:00+00:99", 1790806860.0),
				Arguments.of("2026-10-01T00:00-00:00:01.5", 1790812801.5),
				Arguments.of("2026-10-01T00:00+23:59:59.999999", 1790726400.000001),
				Arguments.of("9999-12-31T23:59:59-01:00", 253402304399.0),
				Arguments.of("1969-12-31T23:59:59.999999Z", -1e-06));
	}

	@ParameterizedTest
	@MethodSource("refusals")
	void parse_textPythonRefuses_isRefusedInPythonsWords(String time, String message) {
		assertThatIllegalArgumentException().isThrownBy(() -> CalibrationTimes.parse(time)).withMessage(message);
	}

	/** Each with the error the Python engine's parse_time raises. */
	static Stream<Arguments> refusals() {
		String offset = "offset must be a timedelta strictly between -timedelta(hours=24) and timedelta(hours=24), ";
		return Stream.of(Arguments.of("nope", "Invalid isoformat string: 'nope'"),
				Arguments.of("2026-10-01T", "Invalid isoformat string: '2026-10-01T'"),
				Arguments.of("2026-W404", "Invalid isoformat string: '2026-W404'"),
				Arguments.of("2026-10-01T12:30:45.", "Invalid isoformat string: '2026-10-01T12:30:45.'"),
				Arguments.of("2026-10-01T00:00+050", "Invalid isoformat string: '2026-10-01T00:00+050'"),
				Arguments.of("2026-10-01T00:00:00z", "Invalid isoformat string: '2026-10-01T00:00:00z'"),
				Arguments.of("2026-10-01T00:00:00+05:00Z",
						"Invalid isoformat string: '2026-10-01T00:00:00+05:00+00:00'"),
				Arguments.of("2026-10-01T24:00:00", "hour must be in 0..23"),
				Arguments.of("2026-10-01T23:60", "minute must be in 0..59"),
				Arguments.of("2026-10-01T23:59:60", "second must be in 0..59"),
				Arguments.of("2026-13-01", "month must be in 1..12"),
				Arguments.of("2026-02-30", "day is out of range for month"),
				Arguments.of("0000-01-01", "year 0 is out of range"),
				Arguments.of("9999-W52-7", "year 10000 is out of range"),
				Arguments.of("2026-10-01T00:00-24:00", offset + "not datetime.timedelta(days=-1)."),
				Arguments.of("2026-13-01T00+25:00", offset + "not datetime.timedelta(days=1, seconds=3600)."));
	}

	@Test
	void ofNanoseconds_aNanosecondShortOfASecond_roundsUpAsPythonsMicrosecondsDo() {
		assertThat(CalibrationTimes.ofNanoseconds(1_999_999_999)).isEqualTo("1970-01-01T00:00:02Z");
	}

	@Test
	void ofNanoseconds_time_isWrittenToTheSecond() {
		assertThat(CalibrationTimes.ofNanoseconds(1_790_812_800_000_000_000L)).isEqualTo("2026-10-01T00:00:00Z");
	}

}
