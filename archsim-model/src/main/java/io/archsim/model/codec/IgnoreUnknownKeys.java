package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A record whose unknown keys are left out of what is read rather than refused, as a
 * pydantic model with {@code extra="ignore"} (pydantic's default) reads them. Without it
 * a record forbids them, as the design document's models do. The API's request bodies are
 * such records: FastAPI's request models ignored unknown keys. Its JSON Schema says
 * nothing of unknown keys (no {@code "additionalProperties": false}), as pydantic's says
 * nothing, and so neither do the API's OpenAPI request bodies. It applies to the record
 * alone: a record it holds keeps its own rule.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IgnoreUnknownKeys {

}
