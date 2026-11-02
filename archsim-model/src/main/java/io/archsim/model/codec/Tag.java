package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value of its {@link Tagged} union's property that selects this record. The record
 * has no component for it: the codec reads and writes the property, first, as pydantic
 * writes its {@code kind: Literal[...]} field.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tag {

	/**
	 * The union property's value that selects this record.
	 * @return the tag's string
	 */
	String value();

}
