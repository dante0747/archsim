package io.archsim.app.tokens;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Person;
import io.archsim.app.web.ApiBody;

/**
 * Personal API tokens ({@code Authorization: Bearer asim_pat_...}), managed from a
 * signed-in browser (or without sign-in), never with a token.
 */
@RestController
@RequestMapping("/tokens")
class TokenController {

	private final Tokens tokens;

	TokenController(Tokens tokens) {
		this.tokens = tokens;
	}

	/**
	 * Your tokens that still work (not revoked or expired), newest first.
	 * @param person who is asking
	 * @return the tokens
	 */
	@GetMapping
	List<TokenView> list(Person person) {
		return this.tokens.list(person);
	}

	/**
	 * A new token. Copy {@code token} now: it is never shown again.
	 * @param person who is asking
	 * @param request what it is for and may do
	 * @return {@code 201} with the token and its secret
	 */
	@PostMapping
	ResponseEntity<TokenCreated> create(Person person, @ApiBody TokenCreate request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.tokens.create(person, request));
	}

	/**
	 * Stop a token working, at once and everywhere.
	 * @param person who is asking
	 * @param tokenId the token
	 * @return {@code 204}
	 */
	@DeleteMapping("/{tokenId}")
	ResponseEntity<Void> revoke(Person person, @PathVariable String tokenId) {
		this.tokens.revoke(person, tokenId);
		return ResponseEntity.noContent().build();
	}

}
