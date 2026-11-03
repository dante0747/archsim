package io.archsim.app.access;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import io.archsim.app.store.Text;
import io.archsim.app.web.RequestInvalidException;

/**
 * Reads the {@link RequestedWorkspace} of a request: {@code ?workspace=} (at most 32
 * characters, as FastAPI checked), else the {@code X-ArchSim-Workspace} header.
 */
class RequestedWorkspaceResolver implements HandlerMethodArgumentResolver {

	/** The header that names the workspace a request is about. */
	static final String HEADER = "X-ArchSim-Workspace";

	private static final String QUERY = "workspace";

	private static final int MAX_ID = 32;

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == RequestedWorkspace.class;
	}

	@Override
	public RequestedWorkspace resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) {
		String[] values = request.getParameterValues(QUERY);
		// FastAPI takes the last of a repeated parameter, and counts characters, not
		// UTF-16 units
		String query = (values != null && values.length > 0) ? values[values.length - 1] : null;
		if (query != null && Text.length(query) > MAX_ID) {
			throw RequestInvalidException.queryTooLong(QUERY, query, MAX_ID);
		}
		String header = request.getHeader(HEADER);
		String id = (query != null && !query.isEmpty()) ? query : header;
		return new RequestedWorkspace((id != null && !id.isEmpty()) ? id : null);
	}

}
