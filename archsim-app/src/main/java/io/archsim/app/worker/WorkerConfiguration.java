package io.archsim.app.worker;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The worker role's settings, bound only when the role starts, so the API never reads or
 * refuses them.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

}
