package io.archsim.app.limits;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A route that makes the engine work on a design: from before its body is read until it
 * is answered, a request to it holds one of the engine's slots, and it counts against the
 * asker's requests to the engine (ADR-0108).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EngineRoute {

	/**
	 * Whether the route replays the design, which takes up to minutes: it then holds one
	 * of the replay slots, so it never holds a slot the quick routes need.
	 * @return {@code true} for a calibration
	 */
	boolean replay() default false;

}
