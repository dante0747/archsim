package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The string is exactly {@code value} (a pydantic {@code Literal} of one string), such as
 * a document's {@code kind}. Pair it with {@code @Default} of the same value when it may
 * be left out.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface Const {

	String value();

}
