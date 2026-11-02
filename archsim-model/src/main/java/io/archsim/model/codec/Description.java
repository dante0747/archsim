package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a record or a field means, as the JSON Schema's {@code description} shows it. On a
 * record it is the Python model's docstring, on a field its
 * {@code Field(description=...)}, both word for word: the schema is part of the contract.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.RECORD_COMPONENT })
public @interface Description {

	String value();

}
