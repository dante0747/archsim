package io.archsim.apitests;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A class of black-box tests against a server of {@link #value()}. Its test methods may
 * take an {@link ApiServer} or a fresh {@link ApiClient} of that server as parameters (or
 * of another profile, with {@link On}). The class is skipped when the run has no server
 * of its profile.
 */
@Documented
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ApiExtension.class)
public @interface ApiTest {

	/**
	 * The profile of the server the class's tests run against.
	 * @return the profile, {@link Profile#LOCAL} unless the class names another
	 */
	Profile value() default Profile.LOCAL;

}
