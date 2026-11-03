package io.archsim.app.signin;

import java.util.Arrays;
import java.util.List;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the sign-in callback take what the provider sent as one parameter, read from the
 * query string only, and documents it as FastAPI did: three optional query parameters.
 */
@Configuration(proxyBeanMethods = false)
class SignInConfiguration implements WebMvcConfigurer {

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new CallbackResolver());
	}

	@Bean
	OperationCustomizer callbackParameters() {
		SpringDocUtils.getConfig().addRequestWrapperToIgnore(Callbacks.Callback.class);
		return SignInConfiguration::documentCallback;
	}

	private static Operation documentCallback(Operation operation, HandlerMethod handler) {
		boolean takesCallback = Arrays.stream(handler.getMethodParameters())
			.anyMatch((parameter) -> parameter.getParameterType() == Callbacks.Callback.class);
		if (takesCallback) {
			operation.addParametersItem(query("code", CallbackResolver.MAX_CODE));
			operation.addParametersItem(query("state", CallbackResolver.MAX_STATE));
			operation.addParametersItem(query("error", CallbackResolver.MAX_STATE));
		}
		return operation;
	}

	private static Parameter query(String name, int maxLength) {
		return new QueryParameter().name(name).schema(new StringSchema().maxLength(maxLength));
	}

}
