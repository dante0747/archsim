package io.archsim.engine.calibration.prometheus.http;

import java.util.List;

import io.archsim.engine.calibration.prometheus.PrometheusException;

/**
 * What a policy asks of a Prometheus URL before a calibration calls it, the URL read as
 * Python 3.12's {@code urllib.parse.urlsplit} reads it.
 */
public final class PrometheusUrls {

	private PrometheusUrls() {
	}

	/**
	 * Whether a URL names an HTTP or HTTPS server: {@code http(s)://host[:port][/path]}.
	 * @param url the URL
	 * @return whether its scheme is {@code http} or {@code https} and it names a host
	 * @throws PrometheusException when its brackets are unbalanced or hold no IPv6
	 * address, or its host holds characters that turn into a delimiter under NFKC
	 * normalization
	 */
	public static boolean namesHttpServer(String url) {
		PrometheusUrl parts = PrometheusUrl.split(url);
		return parts.isHttp() && !parts.netloc().isEmpty();
	}

	/**
	 * Whether a URL's host is on an allowlist.
	 * @param url the URL
	 * @param allowedHosts the hosts, {@code host} or {@code host:port} (IPv6 as
	 * {@code [::1]:9090}); none allows any host
	 * @return whether its host is allowed
	 * @throws PrometheusException when the URL's brackets or port are not valid
	 */
	public static boolean isAllowed(String url, List<String> allowedHosts) {
		return PrometheusUrl.allowed(url, allowedHosts);
	}

}
