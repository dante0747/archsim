package io.archsim.model.codec;

import java.util.List;
import java.util.Map;

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

	record Versioned(@Const("v1") String version) {
	}

	@IgnoreUnknownKeys
	record Request(String name, @Nullable Part part) {
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

	record Threads(@Choice(4) @Choice(1) @Choice(2) int perCore) {
	}

	record Panel(@Ui(label = "Request rate", unit = "req/s", group = "Load", assumption = true, step = 0.5) double rate,
			@Ui(headline = true, kind = "loadCurve", refType = "kubernetes_cluster", hidden = true) String cluster,
			@Ui double plain, double unmarked) {
	}

	record Conditions(boolean autoscaling, int replicas, double share, Mode mode, String zone,
			@Ui(visibleWhen = "autoscaling=true") double byFlag, @Ui(visibleWhen = "replicas=3") double byCount,
			@Ui(visibleWhen = "share=0.5") double byShare, @Ui(visibleWhen = "mode=ramp") double byMode,
			@Ui(visibleWhen = "zone=eu-west") double byZone) {
	}

	record ConditionWithoutValue(boolean autoscaling, @Ui(visibleWhen = "autoscaling") double byFlag) {
	}

	record ConditionOnUnknownField(@Ui(visibleWhen = "autoscaling=true") double byFlag) {
	}

	record ConditionOnList(List<String> tags, @Ui(visibleWhen = "tags=a") double byTags) {
	}

	record ConditionOnBooleanNotTrueOrFalse(boolean autoscaling, @Ui(visibleWhen = "autoscaling=yes") double byFlag) {
	}

	record ConditionOnUnknownChoice(Mode mode, @Ui(visibleWhen = "mode=rmap") double byMode) {
	}

	record Schedule(@DefaultFrom("WORKING_HOURS") Window window) {

		static final Window WORKING_HOURS = new Window(9, 17);

	}

	record MissingConstant(@DefaultFrom("NOWHERE") Window window) {
	}

	record ConstantOfAnotherType(@DefaultFrom("LABEL") Window window) {

		static final String LABEL = "nine to five";

	}

	record InstanceFieldDefault(@DefaultFrom("fallback") Window window, Window fallback) {
	}

	@TaggedByKey(members = { Round.class, Box.class }, errorType = "outline_kind",
			errorMessage = "an outline is {radius} or {sideLength}")
	sealed interface Outline permits Round, Box {

	}

	@KeyTag("radius")
	record Round(@Gt(0) double radius) implements Outline {
	}

	@KeyTag("sideLength")
	record Box(double sideLength) implements Outline {
	}

	record Sketch(Outline outline) {
	}

	@TaggedByKey(members = Ring.class, errorType = "halo_kind", errorMessage = "a halo is {radius}")
	sealed interface Unlisted permits Ring, Disc {

	}

	@KeyTag("radius")
	record Ring(double radius) implements Unlisted {
	}

	@TaggedByKey(members = Disc.class, errorType = "disc_kind", errorMessage = "a disc is {diameter}")
	sealed interface Misnamed permits Disc {

	}

	@KeyTag("diameter")
	record Disc(double width) implements Unlisted, Misnamed {
	}

	record Halo(Unlisted shape) {
	}

	record Plate(Misnamed shape) {
	}

	record Budget(Map<Mode, Double> limits) {
	}

}
