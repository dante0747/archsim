package io.archsim.app.signin;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Where signing in sends the browser next, with the cookies it sets on the way.
 *
 * @param location the address or path
 * @param cookies the {@code Set-Cookie} headers' values
 */
record Redirect(String location, List<String> cookies) {

	Redirect {
		cookies = List.copyOf(cookies);
	}

	/**
	 * Answers with {@code 302} to the location, quoted as a redirect quotes it, and the
	 * cookies.
	 * @param response the answer, not yet committed
	 */
	void send(HttpServletResponse response) {
		response.setStatus(HttpStatus.FOUND.value());
		response.setHeader(HttpHeaders.LOCATION, Landing.location(this.location));
		this.cookies.forEach((cookie) -> response.addHeader(HttpHeaders.SET_COOKIE, cookie));
	}

}
