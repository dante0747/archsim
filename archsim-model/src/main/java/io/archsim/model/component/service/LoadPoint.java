package io.archsim.model.component.service;

import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;

/**
 * One point of a service-time curve (schema 1.6): at {@code rps} requests per second on one
 * replica, the service time is {@code factor} times the configured one.
 *
 * @param rps requests per second one replica receives
 * @param factor the service time there, as a multiple of the configured service time
 */
public record LoadPoint(
        @Gt(0) @Le(10_000_000) double rps, @Gt(0) @Le(100) double factor) {}
