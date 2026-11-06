package io.archsim.engine.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.text.PythonText;

/**
 * A part's settings read as the Python engine read the raw settings of a design that may
 * not be valid yet (ADR-0038, ADR-0042): the load estimate, the review and the exports
 * run on designs being edited, so a setting that is not a value of the right kind counts
 * as missing, and each reader says what missing means. Everywhere else, the engine reads
 * a part's config as its type's record.
 */
public final class LenientSettings {

	/** The 95th percentile of the standard normal distribution. */
	private static final double Z95 = 1.6448536269514722;

	private final JsonNode config;

	private LenientSettings(JsonNode config) {
		this.config = config;
	}

	/**
	 * A part's settings as the Python engine saw them in a normalized design: its config
	 * with every default written out when it is valid for its type, as written otherwise.
	 * @param spec the part's component type, if the registry knows it
	 * @param config the part's config, as the design holds it
	 * @return the settings
	 */
	public static LenientSettings ofNode(Optional<ComponentSpec<?>> spec, JsonNode config) {
		if (spec.isEmpty() || !(config instanceof ObjectNode object)) {
			return new LenientSettings(config);
		}
		try {
			return new LenientSettings(RecordCodec.write(spec.get().parseConfig(object)));
		}
		catch (BindingException ex) {
			// read as it is written: validation reports it
			return new LenientSettings(config);
		}
	}

	/**
	 * Settings exactly as a config holds them.
	 * @param config the config
	 * @return the settings
	 */
	public static LenientSettings of(JsonNode config) {
		return new LenientSettings(config);
	}

	/**
	 * A part's settings over its type's defaults: a setting the config leaves out has its
	 * default, a setting it has is read as it is. A default of {@code null} is left out,
	 * as the Python engine's {@code default_config()} leaves it out.
	 * @param spec the part's component type
	 * @param config the part's config
	 * @return the settings
	 */
	public static LenientSettings overDefaults(ComponentSpec<?> spec, JsonNode config) {
		ObjectNode merged = RecordCodec.write(spec.parseConfig(JsonNodeFactory.instance.objectNode()));
		merged.properties().removeIf((setting) -> setting.getValue().isNull());
		if (config instanceof ObjectNode object) {
			merged.setAll(object);
		}
		return new LenientSettings(merged);
	}

	/**
	 * A number setting.
	 * @param name the setting's JSON name
	 * @return its value when it is a finite number (a boolean is not); empty otherwise
	 */
	public OptionalDouble number(String name) {
		return finite(this.config.path(name));
	}

	/**
	 * A number setting that counts as missing when it is zero, as Python's
	 * {@code value or default} reads it.
	 * @param name the setting's JSON name
	 * @return its value when it is a finite number other than zero; empty otherwise
	 */
	public OptionalDouble nonZero(String name) {
		OptionalDouble value = number(name);
		return (value.isPresent() && value.getAsDouble() != 0) ? value : OptionalDouble.empty();
	}

	/**
	 * A text setting.
	 * @param name the setting's JSON name
	 * @return its value when it is a string; empty otherwise
	 */
	public Optional<String> text(String name) {
		JsonNode value = this.config.path(name);
		return value.isString() ? Optional.of(value.stringValue()) : Optional.empty();
	}

	/**
	 * Whether a setting is a given text.
	 * @param name the setting's JSON name
	 * @param text the text
	 * @return {@code true} when the setting is that string
	 */
	public boolean is(String name, String text) {
		return text(name).filter(text::equals).isPresent();
	}

	/**
	 * Whether a setting is written as {@code null}: the config has it, without a value.
	 * @param name the setting's JSON name
	 * @return {@code true} only for the JSON value {@code null}; not for a setting the
	 * config leaves out
	 */
	public boolean isNull(String name) {
		return this.config.path(name).isNull();
	}

	/**
	 * A setting of any kind, as the config holds it, for a reader that writes it out as
	 * it is (an export's annotations and labels).
	 * @param name the setting's JSON name
	 * @return a copy of its value; a missing node when the config leaves it out
	 */
	public JsonNode value(String name) {
		return this.config.path(name).deepCopy();
	}

	/**
	 * Whether a setting is exactly {@code true}.
	 * @param name the setting's JSON name
	 * @return {@code true} only for the JSON value {@code true}
	 */
	public boolean isTrue(String name) {
		JsonNode value = this.config.path(name);
		return value.isBoolean() && value.booleanValue();
	}

	/**
	 * Whether a setting is exactly {@code false}, as Python's {@code value is False}
	 * tests it.
	 * @param name the setting's JSON name
	 * @return {@code true} only for the JSON value {@code false}
	 */
	public boolean isFalse(String name) {
		JsonNode value = this.config.path(name);
		return value.isBoolean() && !value.booleanValue();
	}

	/**
	 * Whether a setting counts as true, as Python's {@code bool()} counts it.
	 * @param name the setting's JSON name
	 * @param whenMissing what a setting the config leaves out counts as
	 * @return whether it counts as true
	 */
	public boolean truthy(String name, boolean whenMissing) {
		JsonNode value = this.config.path(name);
		return value.isMissingNode() ? whenMissing : PythonText.truthy(value);
	}

	/**
	 * The mean of a distribution setting, read from its JSON by its kind, as the Python
	 * engine's {@code mean_ms} reads it: fields the kind does not use are ignored.
	 * @param name the setting's JSON name
	 * @return its mean, in milliseconds; zero when it is not a distribution of a known
	 * kind
	 * @throws IllegalArgumentException when a field its kind needs is not a number
	 */
	public double meanMs(String name) {
		JsonNode distribution = this.config.path(name);
		return switch (distribution.path("kind").asString("")) {
			case "constant" -> field(distribution, "value");
			case "exponential" -> field(distribution, "mean");
			case "uniform" -> (field(distribution, "min") + field(distribution, "max")) / 2;
			case "lognormal" -> logNormalMean(field(distribution, "median"), field(distribution, "p95"));
			case "empirical" -> empiricalMean(distribution.path("quantiles"));
			default -> 0.0;
		};
	}

	/**
	 * A service's time per request by its load.
	 * @param name the setting's JSON name, {@code serviceTimeByLoad}
	 * @return the curve: flat unless the setting is a list of points
	 */
	public LoadCurve loadCurve(String name) {
		JsonNode points = this.config.path(name);
		return points.isArray() ? LoadCurve.ofJson(points) : LoadCurve.FLAT;
	}

	private static double logNormalMean(double median, double p95) {
		double sigma = Math.log(p95 / median) / Z95;
		return Math.exp(Math.log(median) + sigma * sigma / 2);
	}

	/**
	 * The mean of a quantile table, linear between its points: the trapezoids' area.
	 * @param quantiles the table's {@code [p, ms]} points
	 * @return the mean, in milliseconds
	 */
	private static double empiricalMean(JsonNode quantiles) {
		List<double[]> points = new ArrayList<>();
		quantiles.forEach((point) -> points.add(new double[] { field(point, 0), field(point, 1) }));
		double[] areas = new double[Math.max(0, points.size() - 1)];
		for (int i = 1; i < points.size(); i++) {
			double[] low = points.get(i - 1);
			double[] high = points.get(i);
			areas[i - 1] = (high[0] - low[0]) * (low[1] + high[1]) / 2;
		}
		return FloatSum.of(Arrays.stream(areas));
	}

	private static double field(JsonNode distribution, String name) {
		return required(distribution.path(name), name);
	}

	private static double field(JsonNode point, int index) {
		return required(point.path(index), "a quantile");
	}

	private static double required(JsonNode value, String name) {
		return finite(value).orElseThrow(() -> new IllegalArgumentException(name + " is not a number"));
	}

	private static OptionalDouble finite(JsonNode value) {
		if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(value.doubleValue());
	}

}
