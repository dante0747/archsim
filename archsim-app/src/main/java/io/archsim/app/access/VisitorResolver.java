package io.archsim.app.access;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Gives a controller parameter of type {@link Visitor} where the request comes from.
 */
class VisitorResolver implements HandlerMethodArgumentResolver {

	private final Visitors visitors;

	VisitorResolver(Visitors visitors) {
		this.visitors = visitors;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == Visitor.class;
	}

	@Override
	public Visitor resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) {
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		if (servletRequest == null) {
			throw new IllegalStateException("a visitor is only known for a servlet request");
		}
		return this.visitors.of(servletRequest);
	}

}
