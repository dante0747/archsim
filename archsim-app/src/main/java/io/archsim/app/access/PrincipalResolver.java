package io.archsim.app.access;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Gives a controller parameter of type {@link Principal} who is asking, or of type
 * {@link Person} who is asking in person, as {@link Principals} reads it from Spring
 * Security's context. A request is signed in once, however many parameters ask.
 */
class PrincipalResolver implements HandlerMethodArgumentResolver {

	private final Principals principals;

	/**
	 * A resolver of the principals given.
	 * @param principals who is asking, by request
	 */
	PrincipalResolver(Principals principals) {
		this.principals = principals;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == Principal.class || parameter.getParameterType() == Person.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) {
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		if (servletRequest == null) {
			throw new IllegalStateException("a principal is only known for a servlet request");
		}
		Principal principal = this.principals.of(servletRequest);
		if (parameter.getParameterType() != Person.class) {
			return principal;
		}
		principal.requirePerson();
		return new Person(principal);
	}

}
