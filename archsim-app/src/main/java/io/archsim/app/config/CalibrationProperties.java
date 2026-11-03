package io.archsim.app.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import io.archsim.model.codec.JsonEnum;

/**
 * The settings of {@code POST /calibrate}, from the same {@code ARCHSIM_*} environment
 * variables as the Python API: which Prometheus servers it may call, how long a query and
 * its replay may take, and how much a server may send.
 *
 * @param outbound calls to servers a request names: {@code open} (any host),
 * {@code allowlist} (only the allowed hosts, whose names must resolve to public
 * addresses) or {@code off}; empty for {@code open} without sign-in and, with it,
 * {@code allowlist} when hosts are listed, else {@code off}
 * @param prometheusAllowedHosts the Prometheus hosts a calibration may query
 * ({@code host} or {@code host:port}); none for any host, under {@code open}
 * @param prometheusTimeoutS the deadline of one Prometheus query, body included, in
 * seconds
 * @param prometheusMaxBytes the largest Prometheus response accepted
 * @param calibrateMaxWallSeconds the wall-clock limit of the replay inside a calibration,
 * never above {@code ARCHSIM_MAX_WALL_SECONDS}
 */
@ConfigurationProperties("archsim")
@Validated
public record CalibrationProperties(@Nullable Outbound outbound,
		@DefaultValue("") PrometheusHosts prometheusAllowedHosts,
		@DefaultValue("30.0") @Positive @DecimalMax("600") double prometheusTimeoutS,
		@DefaultValue("52428800") @Min(1024) long prometheusMaxBytes,
		@DefaultValue("60.0") @Positive @DecimalMax("3600") double calibrateMaxWallSeconds) {

	/**
	 * How freely a request may call a server it names.
	 */
	public enum Outbound implements JsonEnum {

		/** Any host (local use). */
		OPEN("open"),

		/** Only the allowed hosts, whose names must resolve to public addresses. */
		ALLOWLIST("allowlist"),

		/** None: only recorded responses and traces. */
		OFF("off");

		private final String json;

		Outbound(String json) {
			this.json = json;
		}

		@Override
		public String json() {
			return this.json;
		}

	}

}
