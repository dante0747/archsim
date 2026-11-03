package io.archsim.app.config;

import java.util.List;

/**
 * The Prometheus hosts calibrations may query ({@code ARCHSIM_PROMETHEUS_ALLOWED_HOSTS}):
 * entries such as {@code prom.internal} or {@code prom.internal:9090}, as a JSON array or
 * separated by commas.
 *
 * @param hosts the hosts, none of them blank
 */
public record PrometheusHosts(List<String> hosts) {

	/**
	 * The hosts, copied, without blank ones.
	 * @param hosts the hosts
	 */
	public PrometheusHosts {
		hosts = hosts.stream().map(String::strip).filter((host) -> !host.isEmpty()).toList();
	}

	/**
	 * Reads the setting. Spring Boot's binder finds this method by its name.
	 * @param setting a JSON array of hosts, or hosts separated by commas
	 * @return the hosts
	 * @throws IllegalArgumentException when the setting starts as a JSON array but is not
	 * one of strings
	 */
	public static PrometheusHosts valueOf(String setting) {
		return new PrometheusHosts(SettingLists.parse("ARCHSIM_PROMETHEUS_ALLOWED_HOSTS", setting));
	}

}
