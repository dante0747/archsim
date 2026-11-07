package io.archsim.engine.calibration.mapping;

import io.archsim.model.codec.Default;

/**
 * A link's latency, from the client's and the server's histograms of the same calls.
 *
 * @param client the client-side histogram query
 * @param server the server-side histogram query
 * @param unit the unit of both histograms' bounds
 */
public record LinkLatencySource(String client, String server, @Default("s") LatencyUnit unit) {

}
