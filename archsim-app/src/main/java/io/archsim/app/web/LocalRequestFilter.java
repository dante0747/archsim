package io.archsim.app.web;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.AllowedHosts;
import io.archsim.app.config.ArchsimProperties;

/**
 * What a server without sign-in answers, where whoever reaches it owns everything in it
 * (ADR-0107).
 * <ul>
 * <li>Only requests addressed to this machine or a name {@code ARCHSIM_ALLOWED_HOSTS}
 * lists ({@code 403}), so a page that points its own name at this machine reaches
 * nothing. The web server's proxy names the host the browser addressed
 * ({@value #CLIENT_HOST}), which must pass too.</li>
 * <li>No change from a page of another site ({@code 403}). A browser marks a change with
 * its page's {@code Origin}, which the proxy passes on ({@value #CLIENT_ORIGIN}); it must
 * be the page the request was addressed to, or one {@code ARCHSIM_CORS_ORIGINS} lists,
 * read as the CORS filter reads it. A listed {@code *} lets every page read, never
 * change.</li>
 * <li>No body but JSON ({@code 415}), which a page cannot send to another site without a
 * preflight the API never grants it.</li>
 * </ul>
 * The proxy's headers need no trust in who sent them, as {@code X-ArchSim-Client-IP}
 * does: they can only add a refusal, never lift one. With sign-in it lets everything
 * through: sessions, CSRF tokens and CORS guard that server. It runs after Spring
 * Security's chain, so its refusals carry the security headers, and after
 * {@link BodySizeFilter}, which refuses a chunked body without a length, so a body here
 * always has one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
final class LocalRequestFilter extends OncePerRequestFilter {

	/** The header the web server's proxy names the host a browser addressed in. */
	private static final String CLIENT_HOST = "X-ArchSim-Client-Host";

	/** The header the web server's proxy passes a browser's {@code Origin} on in. */
	private static final String CLIENT_ORIGIN = "X-ArchSim-Client-Origin";

	/** A JSON type with a suffix, such as {@code application/merge-patch+json}. */
	private static final MediaType ANY_JSON = new MediaType("application", "*+json");

	private final boolean hosted;

	private final AllowedHosts hosts;

	private final CorsConfiguration listedOrigins;

	private final ErrorResponses responses;

	LocalRequestFilter(ArchsimProperties properties, JsonMapper json) {
		this.hosted = properties.hosted();
		this.hosts = properties.allowedHosts();
		this.listedOrigins = new CorsConfiguration();
		this.listedOrigins.setAllowedOrigins(properties.corsOrigins().origins());
		this.responses = new ErrorResponses(json);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return this.hosted;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String clientHost = request.getHeader(CLIENT_HOST);
		if (!isAddressedHere(request.getServerName(), clientHost)) {
			refuse(response, HttpStatus.FORBIDDEN, "This server answers only requests addressed to this machine: "
					+ "set ARCHSIM_ALLOWED_HOSTS to give it another name");
			return;
		}
		String addressed = (clientHost != null) ? clientHost : request.getHeader(HttpHeaders.HOST);
		if (CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request) && !mayChange(request, addressed)) {
			refuse(response, HttpStatus.FORBIDDEN, "A change from another site's page is refused: "
					+ "ARCHSIM_CORS_ORIGINS lists the sites that may make one");
			return;
		}
		if (request.getContentLengthLong() > 0 && !isJson(request.getContentType())) {
			this.responses.write(response, ApiException
				.unsupportedMediaType("Send the request body as application/json", MediaType.APPLICATION_JSON));
			return;
		}
		chain.doFilter(request, response);
	}

	/**
	 * Whether a request is addressed to this server: its host, and the host the web
	 * server's proxy says the browser addressed, if it passed one on.
	 * @param host the request's host, without its port
	 * @param clientHost the host and port the browser addressed, if the proxy passed it
	 * on
	 * @return {@code true} when both are this machine's or listed
	 */
	private boolean isAddressedHere(String host, @Nullable String clientHost) {
		return this.hosts.allows(host) && (clientHost == null || this.hosts.allows(hostName(clientHost)));
	}

	/**
	 * Whether a change may go ahead: its page's {@code Origin}, and the one the proxy
	 * passed on, each allowed.
	 * @param request the request
	 * @param addressed the host and port the request was addressed to, if known
	 * @return {@code true} when the change may go ahead
	 */
	private boolean mayChange(HttpServletRequest request, @Nullable String addressed) {
		return mayChangeFrom(request.getHeader(HttpHeaders.ORIGIN), addressed)
				&& mayChangeFrom(request.getHeader(CLIENT_ORIGIN), addressed);
	}

	/**
	 * Whether a page may make a change here: none at all (a script, or the proxy for a
	 * request that came from no page), the page the request was addressed to, or a site
	 * {@code ARCHSIM_CORS_ORIGINS} lists by name.
	 * @param origin the page's origin, if the request came from one
	 * @param addressed the host and port the request was addressed to, if known
	 * @return {@code true} when the change may go ahead
	 */
	private boolean mayChangeFrom(@Nullable String origin, @Nullable String addressed) {
		if (origin == null) {
			return true;
		}
		return isPageAddressed(origin, addressed) || isListed(origin);
	}

	/**
	 * Whether {@code ARCHSIM_CORS_ORIGINS} lists an origin by name, as the CORS filter
	 * reads the setting: without case, and without a trailing slash. A listed {@code *}
	 * names none.
	 * @param origin the page's origin
	 * @return {@code true} for an origin the setting names
	 */
	private boolean isListed(String origin) {
		String listed = this.listedOrigins.checkOrigin(origin);
		return listed != null && !CorsConfiguration.ALL.equals(listed);
	}

	/**
	 * Whether an origin is the page the request was addressed to: the same host and port,
	 * compared without case.
	 * @param origin the page's origin, {@code "null"} for a page with none to tell
	 * @param addressed the host and port the request was addressed to, if known
	 * @return {@code true} for the page's own server
	 */
	private static boolean isPageAddressed(String origin, @Nullable String addressed) {
		if (addressed == null) {
			return false;
		}
		try {
			String authority = new URI(origin).getRawAuthority();
			return addressed.equalsIgnoreCase(authority);
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	/**
	 * A host as a request's host has it, without its port: {@code [::1]} keeps its
	 * brackets.
	 * @param hostAndPort a {@code Host} header's value
	 * @return the host
	 */
	private static String hostName(String hostAndPort) {
		if (!hostAndPort.isEmpty() && hostAndPort.charAt(0) == '[') {
			int end = hostAndPort.indexOf(']');
			return (end > 0) ? hostAndPort.substring(0, end + 1) : hostAndPort;
		}
		int colon = hostAndPort.indexOf(':');
		return (colon >= 0) ? hostAndPort.substring(0, colon) : hostAndPort;
	}

	private static boolean isJson(@Nullable String type) {
		if (type == null) {
			return false;
		}
		try {
			MediaType media = MediaType.parseMediaType(type);
			return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(media) || ANY_JSON.includes(media);
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

	private void refuse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		this.responses.write(response, new ApiException(status, message, List.of()));
	}

}
