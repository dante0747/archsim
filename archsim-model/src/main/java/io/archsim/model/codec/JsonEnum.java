package io.archsim.model.codec;

/**
 * An enum whose constants stand for JSON strings (a pydantic {@code Literal} of strings),
 * such as {@code ArrivalProcess.POISSON} for {@code "poisson"}.
 */
public interface JsonEnum {

	/** The JSON string of this constant. */
	String json();

}
