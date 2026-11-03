package io.archsim.app.config;

import java.util.List;

/**
 * Hosts a setting names ({@code ARCHSIM_TRUSTED_PROXIES}): addresses, networks or host
 * names, as a JSON array or separated by commas.
 *
 * @param hosts the hosts, none of them blank
 */
public record HostList(List<String> hosts) {

	/**
	 * The hosts, copied, without blank ones: a blank name would be looked up as this
	 * machine.
	 * @param hosts the hosts
	 */
	public HostList {
		hosts = hosts.stream().map(String::strip).filter((host) -> !host.isEmpty()).toList();
	}

	/**
	 * Reads the setting. Spring Boot's binder finds this method by its name.
	 * @param setting a JSON array of hosts, or hosts separated by commas
	 * @return the hosts
	 * @throws IllegalArgumentException when the setting starts as a JSON array but is not
	 * one of strings
	 */
	public static HostList valueOf(String setting) {
		return new HostList(SettingLists.parse("ARCHSIM_TRUSTED_PROXIES", setting));
	}

}
