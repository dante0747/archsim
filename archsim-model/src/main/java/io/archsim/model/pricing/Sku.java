package io.archsim.model.pricing;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;
import io.archsim.model.codec.ModelName;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One instance type of a price list: its shape and its prices per hour.
 *
 * @param sku the instance type, such as {@code c6g.xlarge}
 * @param family its family, such as {@code c6g}
 * @param vcpu its vCPU
 * @param memoryGib its memory, in GiB
 * @param hourly its price per hour by purchase option, such as {@code on_demand}
 * @param engines for databases, caches and streams: the engines it runs
 * @param burstable whether it runs on CPU credits (AWS t families)
 * @param baselineCpu burstable types: the share of each vCPU it can use without spending credits
 * @param creditVcpuHour burstable types in unlimited mode: the price of a vCPU-hour of surplus credits
 */
@ModelName("SkuModel")
public record Sku(
        @MinLength(1) @MaxLength(80) String sku,
        @MinLength(1) @MaxLength(40) String family,
        @Gt(0) @Le(1024) double vcpu,
        @Gt(0) @Le(32_768) double memoryGib,
        Map<String, Double> hourly,
        @Default("[]") List<String> engines,
        @Default("false") boolean burstable,
        @Gt(0) @Le(1) @Nullable Double baselineCpu,
        @Ge(0) @Nullable Double creditVcpuHour) {

    public Sku {
        hourly = Collections.unmodifiableMap(new LinkedHashMap<>(hourly));
        engines = List.copyOf(engines);
    }

    /** The price per hour of a purchase option, if the type has one. */
    public Optional<Double> price(String option) {
        return Optional.ofNullable(hourly.get(option));
    }
}
