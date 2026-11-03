package io.archsim.app.config;

/**
 * An OAuth client's id and secret, as a provider issued them.
 *
 * @param id the client's id
 * @param secret the client's secret, sent with each code
 */
public record ClientCredentials(String id, String secret) {

	/**
	 * The credentials, with the secret masked.
	 * @return the credentials, fit for a log
	 */
	@Override
	public String toString() {
		return "ClientCredentials[id=" + this.id + ", secret=******]";
	}

}
