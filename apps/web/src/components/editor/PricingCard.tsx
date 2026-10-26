"use client";

/**
 * Where the design's prices come from: ArchSim's generic illustrative rates, or a price snapshot
 * on this machine (provider, region, date). With a snapshot, parts snap to real instance types
 * and cost lines cite them (docs/pricing.md).
 */
import { useEffect, useState } from "react";
import { Label, NativeSelect } from "@/components/ui/input";
import { type PricingSource, api } from "@/lib/api";
import { useEditor } from "@/store/editor";

const keyOf = (provider: string, region?: string | null) => `${provider}|${region ?? ""}`;

export function PricingCard() {
  const pricing = useEditor((s) => s.extras?.pricing ?? null);
  const setPricing = useEditor((s) => s.setPricing);
  const [sources, setSources] = useState<PricingSource[] | null>(null);

  useEffect(() => {
    let live = true;
    api
      .pricing()
      .then((r) => live && setSources(r.sources))
      .catch(() => live && setSources(null));
    return () => {
      live = false;
    };
  }, []);

  const current = keyOf(pricing?.provider ?? "generic", pricing?.provider === "generic" ? null : pricing?.region);
  const snapshots = (sources ?? []).filter((s) => !s.illustrative);
  const missing = pricing && pricing.provider !== "generic" && !snapshots.some((s) => keyOf(s.provider, s.region) === current);

  return (
    <div className="rounded-xl bg-surface-2 p-3.5">
      <Label htmlFor="pricing-source" className="microlabel">
        Prices
      </Label>
      <NativeSelect
        id="pricing-source"
        className="mt-1.5 w-full text-xs"
        value={current}
        onChange={(e) => {
          const [provider, region] = e.target.value.split("|");
          setPricing(provider === "generic" ? null : { provider, region: region || null, purchaseOption: "on_demand", families: pricing?.families ?? [] });
        }}
      >
        <option value={keyOf("generic")}>Generic (illustrative rates)</option>
        {snapshots.map((s) => (
          <option key={keyOf(s.provider, s.region)} value={keyOf(s.provider, s.region)}>
            {s.provider} {s.region} · prices of {s.date}
          </option>
        ))}
        {missing && pricing && <option value={current}>{`${pricing.provider} ${pricing.region ?? ""} (no snapshot here)`}</option>}
      </NativeSelect>
      <p className="mt-1.5 text-xs leading-relaxed text-ink-3">
        {pricing && pricing.provider !== "generic"
          ? missing
            ? "This machine has no snapshot for it: generic rates are used until one is added."
            : "Parts snap to the cheapest instance type that covers them; costs cite it."
          : snapshots.length
            ? "Choose a price snapshot to size parts as real instance types."
            : "No price snapshot on this machine yet (see docs/pricing.md)."}
      </p>
    </div>
  );
}
