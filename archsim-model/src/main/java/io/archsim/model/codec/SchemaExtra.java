package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * More JSON Schema for a field, as a JSON object merged into the field's schema
 * (pydantic's {@code json_schema_extra}). The editor reads its {@code x-ui} hints:
 * labels, units and groups.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface SchemaExtra {

	/** A JSON object, such as {@code {"x-ui": {"label": "Seed", "group": "Run"}}}. */
	String value();

}
