package io.archsim.model.codec;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Small records for the codec's tests: one of each kind of field, union, check and layout
 * the codec reads and writes.
 */
final class SampleRecords {

	private SampleRecords() {
	}

	enum Mode implements JsonEnum {

		/** The default. */
		AUTO("auto"),
		/** A second mode. */
		STEADY("steady"),
		/** A third mode, so that a list of the modes reads "a, b or c". */
		RAMP("ramp");

		private final String json;

		Mode(String json) {
			this.json = json;
		}

		@Override
		public String json() {
			return this.json;
		}

	}

	record Sample(@Default("Untitled") @MinLength(1) @MaxLength(10) String name,
			@Default("1.0") @Gt(0) @Le(600_000) double rate, @Default("2") @Ge(1) @Le(10) int count,
			@Default("false") boolean enabled, @Ge(0.1) @Nullable Double share,
			@Default("[]") @MaxItems(2) List<@Matches("^[a-z]+$") String> tags, @Default("auto") Mode mode) {
	}

	record Part(String id, int size, @Default("0.5") double readRatio) {
	}

	@Tagged("kind")
	sealed interface Figure permits Circle, Square {

	}

	@Tag("circle")
	record Circle(@Gt(0) double radius) implements Figure {
	}

	@Tag("square")
	record Square(@Gt(0) double side) implements Figure {
	}

	record Drawing(Figure figure) {
	}

	record Window(double start, double end) {
		Window {
			if (end <= start) {
				throw new IllegalArgumentException("end must come after start");
			}
		}
	}

	@AsArray
	record Point(double t, double value) {
	}

	record Curve(@MinItems(2) List<Point> points) {
	}

	record Settings(@Default("auto") @OmitWhenDefault Mode mode, @OmitWhenDefault @Nullable Double extra) {
	}

}
