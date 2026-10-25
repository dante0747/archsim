"use client";

import { Copy, FlaskConical, ListChecks, MoreHorizontal, Pencil, Play, Plus, Trash2, X } from "lucide-react";
import { useState } from "react";
import { describeValue, FieldControl } from "@/components/editor/FieldControl";
import { PatternEditor } from "@/components/editor/WorkloadPanel";
import { Button } from "@/components/ui/button";
import { Input, Label, NativeSelect } from "@/components/ui/input";
import { Empty, Tip } from "@/components/ui/misc";
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { getPath, uniqueId } from "@/lib/document";
import { componentOf } from "@/lib/graph-rules";
import type { Catalog, FieldDescriptor, Override, Scenario, TrafficPattern } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

type TargetKey = string; // "workload" | "node:<id>" | "edge:<id>"

function targetKey(o: Override): TargetKey {
  return o.target.kind === "workload" ? "workload" : `${o.target.kind}:${o.target.id}`;
}

function useTargetFields() {
  const catalog = useEditor((s) => s.catalog);
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const workload = useEditor((s) => s.workload);
  const targets: { key: TargetKey; label: string }[] = [
    { key: "workload", label: "Traffic" },
    ...nodes.map((n) => ({ key: `node:${n.id}`, label: n.data.label })),
    ...edges.map((e) => {
      const s = nodes.find((n) => n.id === e.source)?.data.label ?? e.source;
      const t = nodes.find((n) => n.id === e.target)?.data.label ?? e.target;
      return { key: `edge:${e.id}`, label: `${s} → ${t}` };
    }),
  ];
  const fieldsFor = (key: TargetKey): FieldDescriptor[] => {
    if (key === "workload") return catalog?.workloadFields ?? [];
    const [kind, id] = [key.slice(0, key.indexOf(":")), key.slice(key.indexOf(":") + 1)];
    if (kind === "edge") return catalog?.edgeFields ?? [];
    const node = nodes.find((n) => n.id === id);
    return node ? (componentOf(catalog, node.data.type)?.fields ?? []) : [];
  };
  const currentValue = (key: TargetKey, field: FieldDescriptor): unknown => {
    if (key === "workload") return getPath(workload, field.key) ?? field.default;
    const id = key.slice(key.indexOf(":") + 1);
    if (key.startsWith("node:")) return getPath(nodes.find((n) => n.id === id)?.data.config, field.key) ?? field.default;
    return getPath(edges.find((e) => e.id === id)?.data?.config, field.key) ?? field.default;
  };
  const labelFor = (key: TargetKey) => targets.find((t) => t.key === key)?.label ?? key;
  return { targets, fieldsFor, currentValue, labelFor, catalog };
}

function overrideText(o: Override, labelFor: (k: string) => string, fieldsFor: (k: string) => FieldDescriptor[]): string {
  const key = targetKey(o);
  const f = fieldsFor(key).find((x) => x.key === o.path);
  const value =
    o.path === "pattern"
      ? (() => {
          const p = o.value as TrafficPattern;
          if (p.kind === "burst") return `burst to ${p.burstRps}/s at ${p.startS}s for ${p.durationS}s`;
          if (p.kind === "ramp") return `ramp ${p.startRps}→${p.endRps}/s`;
          return "constant";
        })()
      : describeValue(f, o.value);
  return `${labelFor(key)} · ${f?.label ?? o.path} = ${value}`;
}

export function ScenariosPanel() {
  const scenarios = useEditor((s) => s.scenarios);
  const setScenarios = useEditor((s) => s.setScenarios);
  const toDocument = useEditor((s) => s.toDocument);
  const startRun = useRuns((s) => s.startRun);
  const startBatch = useRuns((s) => s.startBatch);
  const starting = useRuns((s) => s.starting);
  const { labelFor, fieldsFor } = useTargetFields();
  const [editing, setEditing] = useState<Scenario | null>(null);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);

  const save = (sc: Scenario) => {
    const next = scenarios.slice();
    if (editingIndex === null) next.push(sc);
    else next[editingIndex] = sc;
    setScenarios(next);
    setEditing(null);
    setEditingIndex(null);
  };

  if (editing) {
    return (
      <ScenarioEditor
        initial={editing}
        existingIds={scenarios.filter((_, i) => i !== editingIndex).map((s) => s.id)}
        onCancel={() => {
          setEditing(null);
          setEditingIndex(null);
        }}
        onSave={save}
      />
    );
  }

  const edit = (i: number) => {
    setEditingIndex(i);
    setEditing(scenarios[i]);
  };
  const duplicate = (sc: Scenario) =>
    setScenarios([...scenarios, { ...structuredClone(sc), id: uniqueId(`${sc.id}-copy`, scenarios.map((x) => x.id)), name: `${sc.name} (copy)` }]);

  return (
    <div className="space-y-3 p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-[13px] font-semibold text-ink" title="Named what-if changes on top of this design, to simulate and compare.">
          Scenarios
        </h3>
        <div className="flex gap-1.5">
          {scenarios.length >= 2 && (
            <Button size="sm" disabled={starting} onClick={() => void startBatch(toDocument(), scenarios.slice(0, 10).map((x) => x.id))} title="Simulate every scenario, then compare them in Results">
              <ListChecks /> Simulate all
            </Button>
          )}
          <Button
            size="sm"
            variant="primary"
            onClick={() => {
              setEditingIndex(null);
              setEditing({ id: "", name: "", description: "", overrides: [] });
            }}
          >
            <Plus /> New
          </Button>
        </div>
      </div>
      {scenarios.length === 0 ? (
        <Empty icon={<FlaskConical />} title="No scenarios yet">
          Try “3 replicas” or “MongoDB instead”, then compare.
        </Empty>
      ) : (
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
          {scenarios.map((sc, i) => {
            const n = sc.overrides.length;
            const details = [sc.description, ...sc.overrides.map((o) => overrideText(o, labelFor, fieldsFor))].filter(Boolean).join("\n");
            return (
              <li key={sc.id} className="flex items-center gap-1 py-1.5 pl-3 pr-1.5">
                <button type="button" onClick={() => edit(i)} className="min-w-0 flex-1 py-0.5 text-left" title={details || undefined}>
                  <span className="block truncate text-[13px] font-medium text-ink">{sc.name}</span>
                  <span className="block text-xs text-ink-3">{n === 0 ? "No changes" : `${n} change${n > 1 ? "s" : ""}`}</span>
                </button>
                <Tip content="Simulate this scenario">
                  <Button variant="ghost" size="icon-sm" disabled={starting} aria-label={`Simulate ${sc.name}`} onClick={() => void startRun(toDocument(), sc.id)}>
                    <Play />
                  </Button>
                </Tip>
                <Menu>
                  <MenuTrigger asChild>
                    <Button variant="ghost" size="icon-sm" aria-label={`More actions for ${sc.name}`}>
                      <MoreHorizontal />
                    </Button>
                  </MenuTrigger>
                  <MenuContent align="end">
                    <MenuItem onSelect={() => edit(i)}>
                      <Pencil /> Edit
                    </MenuItem>
                    <MenuItem onSelect={() => duplicate(sc)}>
                      <Copy /> Duplicate
                    </MenuItem>
                    <MenuSeparator />
                    <MenuItem onSelect={() => setScenarios(scenarios.filter((_, k) => k !== i))}>
                      <Trash2 /> Delete
                    </MenuItem>
                  </MenuContent>
                </Menu>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function ScenarioEditor({
  initial,
  existingIds,
  onSave,
  onCancel,
}: {
  initial: Scenario;
  existingIds: string[];
  onSave: (s: Scenario) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(initial.name);
  const [description, setDescription] = useState(initial.description ?? "");
  const [overrides, setOverrides] = useState<Override[]>(initial.overrides);
  const { targets, fieldsFor, currentValue, catalog } = useTargetFields();
  const workloadDuration = useEditor((s) => s.workload.durationS);

  const addOverride = () => {
    const key = targets.find((t) => t.key.startsWith("node:"))?.key ?? "workload";
    const field = fieldsFor(key)[0];
    if (!field) return;
    setOverrides([...overrides, makeOverride(key, field, currentValue(key, field))]);
  };

  const id = initial.id || uniqueId(name || "scenario", existingIds);
  const valid = name.trim().length > 0;

  return (
    <form
      className="space-y-4 p-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) onSave({ id, name: name.trim(), description: description.trim() || null, overrides });
      }}
    >
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-ink">{initial.id ? "Edit scenario" : "New scenario"}</h3>
        <Button variant="ghost" size="icon-sm" onClick={onCancel} aria-label="Cancel">
          <X />
        </Button>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="sc-name">Name</Label>
        <Input id="sc-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. 3 API replicas" maxLength={120} autoFocus />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="sc-desc">Note</Label>
        <Input id="sc-desc" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Optional" maxLength={240} />
      </div>
      <div className="space-y-2">
        <Label>Changes</Label>
        {overrides.length === 0 && <p className="text-xs text-ink-3">None yet</p>}
        {overrides.map((o, i) => (
          <OverrideRow
            key={i}
            override={o}
            catalog={catalog}
            targets={targets}
            fieldsFor={fieldsFor}
            currentValue={currentValue}
            workloadDuration={workloadDuration}
            onChange={(next) => setOverrides(overrides.map((x, k) => (k === i ? next : x)))}
            onRemove={() => setOverrides(overrides.filter((_, k) => k !== i))}
          />
        ))}
        <Button size="sm" onClick={addOverride}>
          <Plus /> Add change
        </Button>
      </div>
      <div className="flex justify-end gap-2 border-t border-line pt-3">
        <Button onClick={onCancel}>Cancel</Button>
        <Button type="submit" variant="primary" disabled={!valid}>
          Save
        </Button>
      </div>
    </form>
  );
}

function makeOverride(key: TargetKey, field: FieldDescriptor, value: unknown): Override {
  const target =
    key === "workload"
      ? { kind: "workload" as const, id: null }
      : { kind: key.slice(0, key.indexOf(":")) as "node" | "edge", id: key.slice(key.indexOf(":") + 1) };
  return { target, path: field.key, value: structuredClone(value) };
}

function OverrideRow({
  override,
  targets,
  fieldsFor,
  currentValue,
  workloadDuration,
  onChange,
  onRemove,
}: {
  override: Override;
  catalog: Catalog | null;
  targets: { key: TargetKey; label: string }[];
  fieldsFor: (k: TargetKey) => FieldDescriptor[];
  currentValue: (k: TargetKey, f: FieldDescriptor) => unknown;
  workloadDuration: number;
  onChange: (o: Override) => void;
  onRemove: () => void;
}) {
  const key = targetKey(override);
  const fields = fieldsFor(key);
  const field = fields.find((f) => f.key === override.path);
  return (
    <div className="space-y-2 rounded-md border border-line bg-surface-2/40 p-2.5">
      <div className="flex gap-1.5">
        <NativeSelect
          aria-label="Change target"
          value={key}
          onChange={(e) => {
            const k = e.target.value;
            const f = fieldsFor(k)[0];
            if (f) onChange(makeOverride(k, f, currentValue(k, f)));
          }}
        >
          {targets.map((t) => (
            <option key={t.key} value={t.key}>
              {t.label}
            </option>
          ))}
        </NativeSelect>
        <Button variant="ghost" size="icon" aria-label="Remove change" onClick={onRemove}>
          <Trash2 />
        </Button>
      </div>
      <NativeSelect
        aria-label="Setting to change"
        value={override.path}
        onChange={(e) => {
          const f = fields.find((x) => x.key === e.target.value);
          if (f) onChange(makeOverride(key, f, currentValue(key, f)));
        }}
      >
        {fields.map((f) => (
          <option key={f.key} value={f.key}>
            {f.label}
            {f.unit ? ` (${f.unit})` : ""}
          </option>
        ))}
      </NativeSelect>
      {field?.kind === "pattern" ? (
        <PatternEditor
          value={override.value as TrafficPattern}
          durationS={workloadDuration}
          onChange={(p) => onChange({ ...override, value: p })}
        />
      ) : field ? (
        <FieldControl
          field={{ ...field, label: "New value" }}
          value={override.value}
          onChange={(v) => onChange({ ...override, value: v })}
          idPrefix={`ov-${key}-${field.key}`}
        />
      ) : (
        <p className="text-xs text-danger-ink">Unknown setting “{override.path}”.</p>
      )}
    </div>
  );
}
