package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value a field takes when the input leaves it out. A field with neither this nor
 * {@code @Nullable} is required; a {@code @Nullable} field without it defaults to {@code null}.
 *
 * <p>For a {@link String} or {@link JsonEnum} field the value is the string itself
 * ({@code @Default("generic")}); for any other field it is JSON text ({@code @Default("50.0")},
 * {@code @Default("[]")}, {@code @Default("{\"kind\": \"constant\", \"value\": 0.5}")}). It is
 * bound like an input, so a default that does not fit its field fails when the codec first meets
 * the record.
 *
 * <p>A JSON object or array default is what pydantic calls a {@code default_factory}: the JSON
 * Schema does not show it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Default {
    String value();
}
