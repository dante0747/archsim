package io.archsim.app.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;

/**
 * Reads an {@link ApiBody} parameter from the request's JSON body with the model's codec.
 */
class ApiBodyResolver implements HandlerMethodArgumentResolver {

	private final JsonMapper json;

	ApiBodyResolver(JsonMapper json) {
		this.json = json;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(ApiBody.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) throws IOException {
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		byte[] body = (servletRequest != null) ? servletRequest.getInputStream().readAllBytes() : new byte[0];
		if (body.length == 0) {
			throw RequestInvalidException.missingBody();
		}
		JsonNode tree = readTree(body);
		try {
			return RecordCodec.read(tree, parameter.getParameterType());
		}
		catch (BindingException ex) {
			throw RequestInvalidException.of(ex.errors(), tree);
		}
	}

	private JsonNode readTree(byte[] body) {
		try {
			return this.json.readTree(body);
		}
		catch (JacksonException ex) {
			long offset = (ex.getLocation() != null) ? Math.max(0, ex.getLocation().getCharOffset()) : 0;
			throw RequestInvalidException.notJson(offset, ex.getOriginalMessage());
		}
	}

}
