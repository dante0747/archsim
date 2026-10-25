"use client";

/** Shared pieces of the experiment composer: fault icons, editable fields and graph context. */
import { Ban, Snail, Timer, TrendingUp } from "lucide-react";
import { toast } from "sonner";
import { useMemo } from "react";
import { NumberInput } from "@/components/editor/FieldControl";
import { Input, Label, NativeSelect } from "@/components/ui/input";
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import {
  DEFAULT_EXTRA_MS,
  DEFAULT_FACTOR,
  FAULT_KINDS,
  FAULT_META,
  type ChaosPreset,
  type FaultKind,
  type FaultTarget,
  chaosPresets,
  clampFault,
  faultTargets,
  newFault,
  targetNames,
} from "@/lib/faults";
import type { Fault } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

export function FaultIcon({ kind, className }: { kind: FaultKind; className?: string }) {
  const Icon = kind === "outage" ? Ban : kind === "slowdown" ? Snail : Timer;
  return <Icon className={cn("size-3.5 shrink-0", className)} style={{ color: FAULT_META[kind].color }} aria-hidden />;
}

/** Faults, names and valid targets for the current design. */
export function useFaultContext() {
  const workload = useEditor((s) => s.workload);
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const catalog = useEditor((s) => s.catalog);
  const names = useMemo(() => targetNames(nodes, edges), [nodes, edges]);
  const targets = useMemo(() => faultTargets(nodes, edges, catalog), [nodes, edges, catalog]);
  const faults = useMemo(() => workload.faults ?? [], [workload.faults]);
  return { workload, faults, nodes, edges, catalog, names, targets };
}

/** Replace the fault list (undoable; same `key` within a second coalesces into one undo step). */
export function setFaults(faults: Fault[], key = "faults") {
  useEditor.getState().setWorkload({ faults }, key);
}

export function updateFault(id: string, patch: Partial<Fault>, key?: string) {
  const s = useEditor.getState();
  const list = s.workload.faults ?? [];
  setFaults(
    list.map((f) => (f.id === id ? clampFault({ ...f, ...patch }, s.workload.durationS) : f)),
    key ?? `fault:${id}`,
  );
}

export function removeFault(id: string) {
  setFaults((useEditor.getState().workload.faults ?? []).filter((f) => f.id !== id), `fault-remove:${id}`);
}

/** Editable fields of one fault (kind, target, window, parameter, label). */
export function FaultFields({
  fault,
  targets,
  durationS,
  idPrefix,
}: {
  fault: Fault;
  targets: Record<FaultKind, FaultTarget[]>;
  durationS: number;
  idPrefix: string;
}) {
  const options = targets[fault.kind];
  const valid = options.some((t) => t.id === fault.target);
  const pos = (v: number) => (v < 0 ? "Must be ≥ 0" : v >= durationS ? `Must be < ${durationS} s` : null);
  return (
    <div className="grid grid-cols-2 gap-x-2.5 gap-y-2">
      <div className="space-y-1">
        <Label htmlFor={`${idPrefix}-kind`} className="microlabel">
          Kind
        </Label>
        <NativeSelect
          id={`${idPrefix}-kind`}
          value={fault.kind}
          onChange={(e) => {
            const kind = e.target.value as FaultKind;
            const keep = targets[kind].some((t) => t.id === fault.target);
            updateFault(fault.id, {
              kind,
              target: keep ? fault.target : (targets[kind][0]?.id ?? fault.target),
              factor: kind === "slowdown" ? (fault.factor ?? DEFAULT_FACTOR) : undefined,
              extraMs: kind === "latency" ? (fault.extraMs ?? DEFAULT_EXTRA_MS) : undefined,
            });
          }}
          className="font-mono text-xs"
        >
          {FAULT_KINDS.map((k) => (
            <option key={k} value={k}>
              {FAULT_META[k].label.toLowerCase()}
            </option>
          ))}
        </NativeSelect>
      </div>
      <div className="space-y-1">
        <Label htmlFor={`${idPrefix}-target`} className="microlabel">
          {FAULT_META[fault.kind].target === "edge" ? "link" : "component"}
        </Label>
        <NativeSelect
          id={`${idPrefix}-target`}
          value={valid ? fault.target : ""}
          aria-invalid={!valid || undefined}
          onChange={(e) => updateFault(fault.id, { target: e.target.value })}
          className="font-mono text-xs"
        >
          {!valid && <option value="">missing: {fault.target}</option>}
          {options.map((t) => (
            <option key={t.id} value={t.id}>
              {t.label}
            </option>
          ))}
        </NativeSelect>
      </div>
      <div className="space-y-1">
        <Label htmlFor={`${idPrefix}-start`} className="microlabel">
          Starts at
        </Label>
        <NumberInput id={`${idPrefix}-start`} value={fault.startS} unit="s" step={1} validate={pos} onCommit={(v) => updateFault(fault.id, { startS: v })} />
      </div>
      <div className="space-y-1">
        <Label htmlFor={`${idPrefix}-dur`} className="microlabel">
          Lasts
        </Label>
        <NumberInput
          id={`${idPrefix}-dur`}
          value={fault.durationS}
          unit="s"
          step={1}
          validate={(v) => (v <= 0 ? "Must be > 0" : v > durationS ? `Must be ≤ ${durationS} s` : null)}
          onCommit={(v) => updateFault(fault.id, { durationS: v })}
        />
      </div>
      {fault.kind === "slowdown" && (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-factor`} className="microlabel">
            Slower by
          </Label>
          <NumberInput
            id={`${idPrefix}-factor`}
            value={fault.factor ?? DEFAULT_FACTOR}
            unit="×"
            step={0.5}
            validate={(v) => (v < 1 ? "Must be ≥ 1" : v > 100 ? "Must be ≤ 100" : null)}
            onCommit={(v) => updateFault(fault.id, { factor: v })}
          />
        </div>
      )}
      {fault.kind === "latency" && (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-extra`} className="microlabel">
            Added latency
          </Label>
          <NumberInput
            id={`${idPrefix}-extra`}
            value={fault.extraMs ?? DEFAULT_EXTRA_MS}
            unit="ms"
            step={10}
            validate={(v) => (v < 0 ? "Must be ≥ 0" : v > 60000 ? "Must be ≤ 60,000" : null)}
            onCommit={(v) => updateFault(fault.id, { extraMs: v })}
          />
        </div>
      )}
      <div className={cn("space-y-1", fault.kind === "outage" && "col-span-2")}>
        <Label htmlFor={`${idPrefix}-label`} className="microlabel">
          Label
        </Label>
        <Input
          id={`${idPrefix}-label`}
          value={fault.label ?? ""}
          maxLength={120}
          placeholder="optional"
          onChange={(e) => updateFault(fault.id, { label: e.target.value || null }, `fault-label:${fault.id}`)}
          className="text-xs"
        />
      </div>
    </div>
  );
}

export function PresetIcon({ p }: { p: ChaosPreset }) {
  if (p.kind === "spike") return <TrendingUp className="size-3.5 shrink-0 text-accent" aria-hidden />;
  return <FaultIcon kind={p.kind} />;
}

/**
 * "Add failure" menu: ready-made quick tests first (each replaces the planned failures, one
 * variable at a time), then a custom failure on any part or link.
 */
export function AddFaultMenu({ children, align = "start" }: { children: React.ReactElement; align?: "start" | "end" }) {
  const { faults, targets, workload, nodes, edges, catalog } = useFaultContext();
  const presets = useMemo(() => chaosPresets(nodes, edges, catalog, workload), [nodes, edges, catalog, workload]);
  const add = (kind: FaultKind, target: string) => {
    const f = newFault(kind, target, workload);
    setFaults([...faults, f], `fault-add:${f.id}`);
  };
  const quick = (p: ChaosPreset) => {
    useEditor.getState().setWorkload(p.apply(useEditor.getState().workload), `chaos:${p.id}`);
    toast.success(p.title, { description: "Planned. Press Simulate to run it." });
  };
  const kinds = FAULT_KINDS.filter((k) => targets[k].length > 0);
  return (
    <Menu>
      <MenuTrigger asChild>{children}</MenuTrigger>
      <MenuContent align={align} className="max-h-96 w-80 overflow-y-auto">
        {kinds.length === 0 && <MenuItem disabled>Add components to the design first</MenuItem>}
        {presets.length > 0 && (
          <>
            <MenuLabel>Quick tests</MenuLabel>
            {presets.map((p) => (
              <MenuItem key={p.id} onSelect={() => quick(p)} title={p.description}>
                <PresetIcon p={p} />
                <span className="truncate">{p.title}</span>
              </MenuItem>
            ))}
            <MenuSeparator />
          </>
        )}
        {kinds.map((k, i) => (
          <div key={k}>
            {i > 0 && <MenuSeparator />}
            <MenuLabel className="flex items-center gap-1.5">
              <FaultIcon kind={k} className="size-3" /> {FAULT_META[k].label} on…
            </MenuLabel>
            {targets[k].map((t) => (
              <MenuItem key={`${k}-${t.id}`} onSelect={() => add(k, t.id)}>
                {t.label}
              </MenuItem>
            ))}
          </div>
        ))}
      </MenuContent>
    </Menu>
  );
}
