package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One integer the value may be; repeat it for each (a pydantic {@code Literal} of
 * integers): {@code @Choice(1) @Choice(2) int threadsPerCore}. Error messages list the
 * choices in the order they are written.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
@Repeatable(Choice.List.class)
public @interface Choice {

	/**
	 * An integer the value may be.
	 * @return the allowed integer
	 */
	int value();

	/**
	 * The choices of a field, as Java holds repeated annotations.
	 */
	@Documented
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE_USE)
	@interface List {

		/**
		 * The choices, in the order they are written.
		 * @return the choices
		 */
		Choice[] value();

	}

}
