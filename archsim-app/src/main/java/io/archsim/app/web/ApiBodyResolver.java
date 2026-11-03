package io.archsim.app.web;

import java.io.IOException;
import java.util.List;

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
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.Location;
import io.archsim.model.codec.RecordCodec;

/**
 * Reads an {@link ApiBody} parameter from the request's JSON body: a record with the
 * model's codec, an {@link ObjectNode} as any JSON object.
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
	public @Nullable Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) throws IOException {
		JsonNode tree = body(request);
		boolean required = required(parameter);
		if (tree == null) {
			if (required) {
				throw RequestInvalidException.missingBody();
			}
			return null;
		}
		return (tree.isNull() && !required) ? null : read(parameter, tree);
	}

	/**
	 * The request's body, as JSON.
	 * @param request the request
	 * @return the body, or {@code null} when it is empty
	 * @throws IOException when the body cannot be read
	 * @throws RequestInvalidException when the body is not JSON
	 */
	private @Nullable JsonNode body(NativeWebRequest request) throws IOException {
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		byte[] body = (servletRequest != null) ? servletRequest.getInputStream().readAllBytes() : new byte[0];
		return (body.length > 0) ? readTree(body) : null;
	}

	/**
	 * A body read as the parameter's type: a record by the model's codec, an
	 * {@link ObjectNode} as any JSON object.
	 * @param parameter the parameter
	 * @param tree the body
	 * @return the value
	 * @throws RequestInvalidException when the body does not fit the type
	 */
	private static Object read(MethodParameter parameter, JsonNode tree) {
		if (parameter.getParameterType() == ObjectNode.class) {
			return anyObject(tree);
		}
		if (!parameter.getParameterType().isRecord()) {
			throw new IllegalStateException(
					"@ApiBody takes a record or an ObjectNode, not " + parameter.getParameterType());
		}
		try {
			return RecordCodec.read(tree, parameter.getParameterType());
		}
		catch (BindingException ex) {
			throw RequestInvalidException.of(ex.errors(), tree);
		}
	}

	private static boolean required(MethodParameter parameter) {
		ApiBody annotation = parameter.getParameterAnnotation(ApiBody.class);
		return annotation == null || annotation.required();
	}

	/**
	 * A body taken as any JSON object, as pydantic takes {@code dict[str, Any]}.
	 * @param tree the body
	 * @return the object
	 * @throws RequestInvalidException when the body is not an object
	 */
	private static ObjectNode anyObject(JsonNode tree) {
		if (tree instanceof ObjectNode object) {
			return object;
		}
		throw RequestInvalidException
			.of(List.of(new BindingError("dict_type", Location.ROOT, "Input should be a valid dictionary")), tree);
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
