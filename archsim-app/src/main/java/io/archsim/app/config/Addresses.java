package io.archsim.app.config;

/**
 * Web addresses a setting names, written as the Python API wrote them: without the
 * slashes they end with ({@code url.rstrip("/")}).
 */
public final class Addresses {

	private Addresses() {
	}

	/**
	 * An address without the slashes it ends with.
	 * @param url the address
	 * @return the address, with no slash at its end
	 */
	public static String withoutTrailingSlashes(String url) {
		int end = url.length();
		while (end > 0 && url.charAt(end - 1) == '/') {
			end--;
		}
		return url.substring(0, end);
	}

}
