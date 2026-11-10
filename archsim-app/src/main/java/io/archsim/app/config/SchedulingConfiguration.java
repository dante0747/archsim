package io.archsim.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods in the API role, the one role that has any; the
 * worker runs its own periodic tasks on threads of its own.
 */
@Configuration(proxyBeanMethods = false)
@Profile("api")
@EnableScheduling
class SchedulingConfiguration {

}
