package io.archsim.app.signin;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Where signing in sends the browser next, with the cookies it sets on the way.
 *
 * @param location the address or path
 * @param cookies the {@code Set-Cookie} headers' values
 */
record Redirect(String location, List<String> cookies) {

	/**
	 * The answer: {@code 302} to the location, quoted as a redirect quotes it.
	 * @return the response
	 */
	ResponseEntity<Void> answer() {
		return ResponseEntity.status(HttpStatus.FOUND)
			.header(HttpHeaders.LOCATION, Landing.location(this.location))
			.header(HttpHeaders.SET_COOKIE, this.cookies.toArray(String[]::new))
			.build();
	}

}
