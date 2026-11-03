package io.archsim.app.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;

/**
 * Marks a controller parameter that is read from the request's JSON body by the model's
 * codec, as pydantic read it: a body that does not fit is refused with {@code 422} and
 * FastAPI's list of pydantic errors, which the web app shows. The OpenAPI document shows
 * it as the request body, with the codec's JSON Schema ({@code ApiBodyDocumentation}).
 */
@Parameter(hidden = true)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiBody {

}
