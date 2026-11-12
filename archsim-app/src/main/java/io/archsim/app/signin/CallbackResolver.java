package io.archsim.app.signin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import io.archsim.app.web.QueryParameters;
import io.archsim.app.web.RequestInvalidException;

/**
 * Reads a sign-in callback's {@code code}, {@code state} and {@code error} from the query
 * string only, as FastAPI read them: the last of a repeated one counts, and one too long
 * is refused with {@code 422}. Not {@code @RequestParam List<String>} parameters: Spring
 * splits a value given once at its commas.
 */
class CallbackResolver implements HandlerMethodArgumentResolver {

	/** The longest code a provider sends. */
	static final int MAX_CODE = 2000;

	/** The longest state, or error, a provider sends back. */
	static final int MAX_STATE = 200;

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == Callbacks.Callback.class;
	}

	@Override
	public Callbacks.Callback resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) {
		return new Callbacks.Callback(text(request, "code", MAX_CODE), text(request, "state", MAX_STATE),
				text(request, "error", MAX_STATE));
	}

	/**
	 * A query parameter.
	 * @param request the request
	 * @param name the parameter's name
	 * @param maxLength the most characters it may have
	 * @return its last value, or {@code null} when it is not given
	 * @throws RequestInvalidException {@code 422} when it is longer
	 */
	private static @Nullable String text(NativeWebRequest request, String name, int maxLength) {
		String[] values = request.getParameterValues(name);
		return QueryParameters.text((values != null) ? List.of(values) : null, name, maxLength).orElse(null);
	}

}
