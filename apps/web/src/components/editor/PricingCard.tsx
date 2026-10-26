"use client";

/**
 * Where the design's prices come from: ArchSim's generic illustrative rates, or a price snapshot
 * on this machine (provider, region, date). With a snapshot, parts snap to real instance types
 * and cost lines cite them (docs/pricing.md). Choosing a snapshot pins its date, so the design
 * keeps the same sizes and prices when a newer snapshot is added.
 */
import { useEffect, useState } from "react";
import { Label, NativeSelect } from "@/components/ui/input";
import { type PricingSource, api } from "@/lib/api";
import { snapshotInUse } from "@/lib/cost";
import { useEditor } from "@/store/editor";

const GENERIC = "generic";
const UNPINNED = "unpinned";
const MISSING = "missing";
const keyOf = (s: PricingSource) => `${s.provider}|${s.region ?? ""}|${s.date ?? ""}|${s.sha256 ?? ""}`;

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

  const snapshots = (sources ?? []).filter((s) => !s.illustrative);
  const generic = !pricing || pricing.provider === GENERIC;
  // the snapshot the engine uses: the pinned one, else the newest of the provider (and region)
  const used = generic ? null : snapshotInUse(snapshots, pricing);
  const pinned = !generic && !!(pricing?.snapshotDate || pricing?.snapshotSha256);
  const current = generic ? GENERIC : !used ? MISSING : pinned ? keyOf(used) : UNPINNED;
  // the newest snapshot per provider and region, and an older one when the design is pinned to it
  const listed = snapshots.filter((s) => s.newest !== false || (pinned && s === used));
  const where = pricing ? `${pricing.provider}${pricing.region ? ` ${pricing.region}` : ""}` : "";

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
          const value = e.target.value;
          if (value === GENERIC) return setPricing(null);
          const s = snapshots.find((x) => keyOf(x) === value);
          if (!s) return;
          setPricing({
            provider: s.provider,
            region: s.region || null,
            purchaseOption: "on_demand",
            families: pricing?.families ?? [],
            snapshotDate: s.date,
          });
        }}
      >
        <option value={GENERIC}>Generic (illustrative rates)</option>
        {current === UNPINNED && used && <option value={UNPINNED}>{`${where} · newest here (prices of ${used.date})`}</option>}
        {listed.map((s) => (
          <option key={keyOf(s)} value={keyOf(s)}>
            {`${s.provider} ${s.region} · prices of ${s.date}${s.newest === false ? " (older)" : ""}`}
          </option>
        ))}
        {current === MISSING && pricing && <option value={MISSING}>{`${where}${pricing.snapshotDate ? ` of ${pricing.snapshotDate}` : ""} (no snapshot here)`}</option>}
      </NativeSelect>
      <p className="mt-1.5 text-xs leading-relaxed text-ink-3">
        {!generic
          ? current === MISSING
            ? pinned
              ? "This machine does not have that snapshot: generic rates are used until it is added. A pinned snapshot is never swapped for another."
              : "This machine has no snapshot for it: generic rates are used until one is added."
            : current === UNPINNED
              ? "Parts snap to the cheapest instance type that covers them; costs cite it. Not pinned: a newer snapshot would change them. Choose the snapshot to pin it."
              : "Parts snap to the cheapest instance type that covers them; costs cite it."
          : snapshots.length
            ? "Choose a price snapshot to size parts as real instance types."
            : "No price snapshot on this machine yet (see docs/pricing.md)."}
      </p>
    </div>
  );
}
