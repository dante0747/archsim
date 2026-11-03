package io.archsim.app.web;

import java.io.IOException;
import java.io.UncheckedIOException;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;

import io.archsim.model.codec.RecordCodec;

/**
 * Documents each {@link ApiBody} parameter as its operation's request body, with the JSON
 * Schema the model's codec writes for a record, as FastAPI documented its pydantic
 * bodies, or as any JSON object.
 */
@Configuration(proxyBeanMethods = false)
class ApiBodyDocumentation {

	@Bean
	OperationCustomizer apiBodies() {
		return (operation, handler) -> {
			for (MethodParameter parameter : handler.getMethodParameters()) {
				ApiBody body = parameter.getParameterAnnotation(ApiBody.class);
				if (body != null) {
					operation.requestBody(requestBody(parameter.getParameterType(), body.required()));
				}
			}
			return operation;
		};
	}

	private static RequestBody requestBody(Class<?> type, boolean required) {
		Schema<?> schema;
		try {
			schema = type.isRecord()
					? Json.mapper()
						.readValue(RecordCodec.jsonSchema(type.asSubclass(Record.class)).toString(), Schema.class)
					: new ObjectSchema();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("the codec's schema of " + type.getSimpleName() + " is not one", ex);
		}
		MediaType json = new MediaType().schema(schema);
		return new RequestBody().required(required)
			.content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, json));
	}

}
