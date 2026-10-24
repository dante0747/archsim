/** Minimal YAML emitter for generated manifests (block style, JSON-quoted strings when needed). */

export type YamlValue = string | number | boolean | null | undefined | YamlValue[] | { [key: string]: YamlValue };

const PLAIN = /^[A-Za-z_/.][A-Za-z0-9_./@:-]*$/;
const RESERVED = /^(true|false|yes|no|on|off|null|~|y|n)$/i;

export function scalar(v: string | number | boolean | null): string {
  if (v === null) return "null";
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (PLAIN.test(v) && !RESERVED.test(v) && !v.endsWith(":") && !v.includes(": ")) return v;
  return JSON.stringify(v);
}

const isScalar = (v: YamlValue): v is string | number | boolean | null => v === null || typeof v !== "object";

function emit(v: YamlValue, indent: number): string[] {
  const pad = " ".repeat(indent);
  if (Array.isArray(v)) {
    const lines: string[] = [];
    for (const item of v) {
      if (item === undefined) continue;
      if (isScalar(item)) lines.push(`${pad}- ${scalar(item)}`);
      else if (Array.isArray(item)) {
        if (!item.length) lines.push(`${pad}- []`);
        else lines.push(`${pad}-`, ...emit(item, indent + 2));
      } else {
        const inner = emit(item, indent + 2);
        if (!inner.length) lines.push(`${pad}- {}`);
        else lines.push(`${pad}- ${inner[0].slice(indent + 2)}`, ...inner.slice(1));
      }
    }
    return lines;
  }
  if (v && typeof v === "object") {
    const lines: string[] = [];
    for (const [k, val] of Object.entries(v)) {
      if (val === undefined) continue;
      const key = scalar(k);
      if (isScalar(val)) lines.push(`${pad}${key}: ${scalar(val)}`);
      else if (Array.isArray(val) && !val.some((x) => x !== undefined)) lines.push(`${pad}${key}: []`);
      else if (!Array.isArray(val) && !Object.values(val).some((x) => x !== undefined)) lines.push(`${pad}${key}: {}`);
      else lines.push(`${pad}${key}:`, ...emit(val, Array.isArray(val) ? indent + 2 : indent + 2));
    }
    return lines;
  }
  return [`${pad}${scalar(v as string | number | boolean | null)}`];
}

/** YAML text of `value` (no trailing newline). */
export function toYaml(value: YamlValue): string {
  return emit(value, 0).join("\n");
}

/** `# ...` comment lines. */
export function comments(lines: string[]): string {
  return lines.map((l) => (l ? `# ${l}` : "#")).join("\n");
}
