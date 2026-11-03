package io.archsim.app.access;

import java.util.Arrays;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

/**
 * The OpenAPI document's view of who is asking and where: a {@link Principal},
 * {@link Person} or {@link Visitor} parameter is no request parameter, and a
 * {@link RequestedWorkspace} is the {@code workspace} query parameter or the
 * {@code X-ArchSim-Workspace} header, as FastAPI documented them.
 */
@Configuration(proxyBeanMethods = false)
class AccessDocumentation {

	private static final int MAX_WORKSPACE_ID = 32;

	@Bean
	OperationCustomizer requestedWorkspaces() {
		SpringDocUtils.getConfig()
			.addRequestWrapperToIgnore(Principal.class, Person.class, Visitor.class, RequestedWorkspace.class);
		return AccessDocumentation::documentWorkspace;
	}

	private static Operation documentWorkspace(Operation operation, HandlerMethod handler) {
		boolean namesWorkspace = Arrays.stream(handler.getMethodParameters())
			.anyMatch((parameter) -> parameter.getParameterType() == RequestedWorkspace.class);
		if (namesWorkspace) {
			operation.addParametersItem(new QueryParameter().name("workspace")
				.description("Workspace id (or the X-ArchSim-Workspace header). Default: your personal "
						+ "workspace, else the default one.")
				.schema(new StringSchema().maxLength(MAX_WORKSPACE_ID)));
			operation.addParametersItem(
					new HeaderParameter().name(RequestedWorkspaceResolver.HEADER).schema(new StringSchema()));
		}
		return operation;
	}

}
