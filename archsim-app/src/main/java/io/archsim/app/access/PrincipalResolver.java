package io.archsim.app.access;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.ApiException;

/**
 * Gives a controller parameter of type {@link Principal} who is asking. Without sign-in
 * that is the local user, from the address the request comes from:
 * {@code X-ArchSim-Client-IP} when a trusted proxy sends it, else the peer's.
 */
class PrincipalResolver implements HandlerMethodArgumentResolver {

	/** The visitor's address, as the web server saw it. */
	static final String CLIENT_IP_HEADER = "X-ArchSim-Client-IP";

	private final ArchsimProperties properties;

	private final TrustedProxies proxies;

	PrincipalResolver(ArchsimProperties properties, TrustedProxies proxies) {
		this.properties = properties;
		this.proxies = proxies;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == Principal.class;
	}

	@Override
	public Principal resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) {
		if (this.properties.hosted()) {
			// sessions and API tokens come with slice 3 of the port
			throw ApiException.unauthorized("Sign in to continue");
		}
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		return Principal.local((servletRequest != null) ? clientIp(servletRequest).orElse(null) : null);
	}

	/**
	 * The address a request comes from, as the history records it.
	 * @param request the request
	 * @return the forwarded address when a trusted proxy sends a valid one, else the
	 * peer's
	 */
	Optional<String> clientIp(HttpServletRequest request) {
		Optional<Ip> peer = IpText.parse(request.getRemoteAddr());
		String forwarded = request.getHeader(CLIENT_IP_HEADER);
		if (forwarded != null && peer.isPresent() && this.proxies.trust(peer.get())) {
			Optional<Ip> client = IpText.parse(forwarded.strip());
			if (client.isPresent()) {
				return client.map(IpText::format);
			}
		}
		return peer.map(IpText::format);
	}

}
