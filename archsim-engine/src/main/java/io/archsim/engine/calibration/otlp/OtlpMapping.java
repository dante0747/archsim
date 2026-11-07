package io.archsim.engine.calibration.otlp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Which part of a design each OpenTelemetry service and database is, and the span
 * attribute with a SERVER span's CPU time.
 *
 * @param services each service's part id, by its {@code service.name}
 * @param databases each database's part id, by its {@code db.name} or {@code db.system}
 * @param cpuTime the attribute with a SERVER span's CPU time, and its unit; {@code null}
 * for none
 */
public record OtlpMapping(Map<String, String> services, Map<String, String> databases, @Nullable CpuAttribute cpuTime) {

	/**
	 * Copies the names.
	 */
	public OtlpMapping {
		services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
		databases = Collections.unmodifiableMap(new LinkedHashMap<>(databases));
	}

	/**
	 * A span attribute with a SERVER span's CPU time, and the unit it is written in.
	 *
	 * @param attribute the attribute's key
	 * @param unit its unit
	 */
	public record CpuAttribute(String attribute, CpuUnit unit) {

		/**
		 * The attribute with the milliseconds per unit of its value, as the summary reads
		 * it.
		 * @return the attribute and its milliseconds per unit
		 */
		public CpuTime toCpuTime() {
			return new CpuTime(this.attribute, this.unit.milliseconds());
		}

	}

}
