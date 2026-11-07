package io.archsim.engine.calibration.mapping;

import io.archsim.model.codec.TaggedByKey;

/**
 * Where a part's setting comes from in telemetry, told apart by the key that names it: a
 * histogram, a gauge, a hit ratio, CPU per request or the working set. The members' order
 * is the Python engine's ({@code _SOURCE_KEYS}): an entry with the keys of several is
 * read as the first, its other keys refused as extra.
 */
@TaggedByKey(
		members = { HistogramSource.class, GaugeSource.class, RatioSource.class, CpuPerRequestSource.class,
				WorkingSetSource.class },
		errorType = "source_kind",
		errorMessage = "a node entry is one of {histogram}, {gauge}, {ratio}, {cpu, requests} or "
				+ "{workingSet, requests|inflight}")
public sealed interface NodeSource
		permits HistogramSource, GaugeSource, RatioSource, CpuPerRequestSource, WorkingSetSource {

}
