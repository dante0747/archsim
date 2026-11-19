"use client";

/**
 * How the design's vCPUs count (schema 1.11, ADR-0091): its sibling speed, architecture.siblingSpeed,
 * for every service, database and cache with 2 or more vCPU that sets none. Empty, each part's instance
 * family decides: whole cores where the family's vCPUs are cores (Graviton), for serverless and pods,
 * otherwise hyperthread pairs at 0.6. A design saved before 1.11 is pinned to 1, so it keeps its results.
 * The field comes from the engine's catalog; a server that sends none gets no card.
 */
import { type AutoHint, FieldControl } from "@/components/editor/FieldControl";
import { useEditor } from "@/store/editor";

const KEY = "siblingSpeed";

/** An empty setting: each part's instance family decides; set, it starts from whole cores. */
const BY_FAMILY: AutoHint = { value: 1, source: "each part's instance family", text: "each part's instance family" };

function meaning(siblingSpeed: number | null, hosted: boolean): string {
  if (hosted) return "The design sets a host machine, whose sibling speed applies instead.";
  if (siblingSpeed === null) return "Each part's instance family decides; x86 types count as hyperthread pairs (see each part's sibling speed).";
  if (siblingSpeed >= 1) return "Whole cores (this design was saved before schema 1.11, or you set it).";
  return `Hyperthread pairs at ${siblingSpeed} on every part that sets none.`;
}

export function SiblingSpeedCard() {
  const field = useEditor((s) => s.catalog?.architectureFields?.find((f) => f.key === KEY));
  const siblingSpeed = useEditor((s) => s.extras?.siblingSpeed ?? null);
  const hosted = useEditor((s) => !!s.extras?.host);
  const setSiblingSpeed = useEditor((s) => s.setSiblingSpeed);
  if (!field) return null;

  return (
    <div className="rounded-xl bg-surface-2 p-3.5">
      <p className="microlabel mb-1.5">{field.group ?? "Machines"}</p>
      <FieldControl
        field={field}
        value={siblingSpeed}
        onChange={(v) => setSiblingSpeed(typeof v === "number" ? v : null)}
        idPrefix="design"
        auto={BY_FAMILY}
      />
      <p className="mt-1.5 text-xs leading-relaxed text-ink-3">
        {meaning(siblingSpeed, hosted)}
        {siblingSpeed !== null && (
          <>
            {" "}
            <button type="button" onClick={() => setSiblingSpeed(null)} className="font-medium text-accent hover:underline">
              Use the default
            </button>
          </>
        )}
      </p>
    </div>
  );
}
