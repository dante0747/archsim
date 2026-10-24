/**
 * Schematic shapes for component kinds, drawn as line art:
 *   traffic  → parallelogram (flowchart "input")
 *   network  → hexagon
 *   compute  → stacked boxes (one layer per extra replica, up to 3)
 *   cache    → memory chip with pins
 *   database → cylinder
 *   other    → box with a cut corner
 * The same geometry draws canvas nodes, rail glyphs and the home-page previews; `unit` scales the
 * decorations (slants, pins, ellipses) for small sizes.
 */
import type { Kind } from "@/lib/theme";

export type Shape = "parallelogram" | "hexagon" | "stack" | "chip" | "cylinder" | "notch";

export const SHAPE_OF: Record<Kind, Shape> = {
  traffic: "parallelogram",
  network: "hexagon",
  compute: "stack",
  cache: "chip",
  database: "cylinder",
  other: "notch",
};

const BASE = { slant: 14, point: 16, layer: 5, pin: 5, pinStep: 16, ry: 8, notch: 14, r: 3 };

/** Padding (px at unit 1) that keeps node content inside each outline. */
export const SHAPE_PADDING: Record<Shape, { left: number; right: number; top: number; bottom: number }> = {
  parallelogram: { left: 24, right: 24, top: 10, bottom: 10 },
  hexagon: { left: 24, right: 24, top: 10, bottom: 10 },
  stack: { left: 12, right: 12 + 2 * BASE.layer, top: 10 + 2 * BASE.layer, bottom: 10 },
  chip: { left: 12, right: 12, top: 14, bottom: 14 },
  cylinder: { left: 12, right: 12, top: 10 + 2 * BASE.ry, bottom: 12 },
  notch: { left: 12, right: 12, top: 10, bottom: 10 },
};

/**
 * Where the link handles sit relative to the bounding box, so wires meet the outline:
 * `left`/`right` are insets from each side, `dy` shifts handles from the vertical centre.
 */
export const SHAPE_HANDLE: Record<Shape, { left: number; right: number; dy: number }> = {
  parallelogram: { left: BASE.slant / 2, right: BASE.slant / 2, dy: 0 },
  hexagon: { left: 0, right: 0, dy: 0 },
  stack: { left: 0, right: 2 * BASE.layer, dy: BASE.layer },
  chip: { left: 0, right: 0, dy: 0 },
  cylinder: { left: 0, right: 0, dy: 0 },
  notch: { left: 0, right: 0, dy: 0 },
};

export interface ShapeSvgProps {
  shape: Shape;
  w: number;
  h: number;
  /** Scale of decorations: 1 for canvas nodes, ~0.35 for small glyphs. */
  unit?: number;
  stroke?: string;
  strokeWidth?: number;
  fill?: string;
  /** Replicas drawn as stacked layers (stack shape only). */
  layers?: number;
  /** Extra attributes for the main outline (e.g. a filter). */
  outlineStyle?: React.CSSProperties;
}

/** The shape as SVG elements (no <svg> wrapper), for use inside any SVG. */
export function ShapeSvg({
  shape,
  w,
  h,
  unit = 1,
  stroke = "var(--node-stroke)",
  strokeWidth = 1.25,
  fill = "var(--node-fill)",
  layers = 1,
  outlineStyle,
}: ShapeSvgProps) {
  const s = strokeWidth / 2;
  const u = (k: keyof typeof BASE) => BASE[k] * unit;
  const common = { stroke, strokeWidth, fill, strokeLinejoin: "round" as const };
  switch (shape) {
    case "parallelogram": {
      const k = u("slant");
      return <path d={`M${k},${s} L${w - s},${s} L${w - k},${h - s} L${s},${h - s} Z`} {...common} style={outlineStyle} />;
    }
    case "hexagon": {
      const k = u("point");
      return (
        <path
          d={`M${k},${s} L${w - k},${s} L${w - s},${h / 2} L${w - k},${h - s} L${k},${h - s} L${s},${h / 2} Z`}
          {...common}
          style={outlineStyle}
        />
      );
    }
    case "stack": {
      const o = u("layer");
      const r = u("r");
      const bw = w - 2 * o - s * 2;
      const bh = h - 2 * o - s * 2;
      const n = Math.max(1, Math.min(3, Math.round(layers)));
      const back = [];
      for (let i = n - 1; i >= 1; i--) {
        back.push(<rect key={i} x={s + o * i} y={s + 2 * o - o * i} width={bw} height={bh} rx={r} {...common} />);
      }
      return (
        <>
          {back}
          <rect x={s} y={s + 2 * o} width={bw} height={bh} rx={r} {...common} style={outlineStyle} />
        </>
      );
    }
    case "chip": {
      const p = u("pin");
      const step = u("pinStep");
      const r = u("r");
      const pins: number[] = [];
      const margin = step * 0.9;
      const count = Math.max(2, Math.floor((w - 2 * margin) / step) + 1);
      const span = (count - 1) * step;
      for (let i = 0; i < count; i++) pins.push((w - span) / 2 + i * step);
      return (
        <>
          {pins.map((x) => (
            <g key={x} stroke={stroke} strokeWidth={strokeWidth} strokeLinecap="square">
              <line x1={x} y1={s} x2={x} y2={p} />
              <line x1={x} y1={h - p} x2={x} y2={h - s} />
            </g>
          ))}
          <rect x={s} y={p} width={w - 2 * s} height={h - 2 * p} rx={r} {...common} style={outlineStyle} />
          {unit >= 0.6 && <circle cx={p + 6 * unit} cy={p + 6 * unit} r={1.6 * unit} fill={stroke} />}
        </>
      );
    }
    case "cylinder": {
      const ry = u("ry");
      const rx = w / 2 - s;
      return (
        <>
          <path
            d={`M${s},${ry} L${s},${h - ry} A${rx},${ry - s} 0 0 0 ${w - s},${h - ry} L${w - s},${ry} A${rx},${ry - s} 0 0 0 ${s},${ry} Z`}
            {...common}
            style={outlineStyle}
          />
          <path d={`M${s},${ry} A${rx},${ry - s} 0 0 0 ${w - s},${ry}`} fill="none" stroke={stroke} strokeWidth={strokeWidth} />
        </>
      );
    }
    case "notch": {
      const k = u("notch");
      return <path d={`M${s},${s} L${w - k},${s} L${w - s},${k} L${w - s},${h - s} L${s},${h - s} Z`} {...common} style={outlineStyle} />;
    }
  }
}

/** Small standalone glyph of a shape (rail, library, inspector headers, legends). */
export function ShapeGlyph({
  shape,
  className,
  stroke = "currentColor",
  fill = "none",
  w = 30,
  h = 20,
  layers = 2,
}: {
  shape: Shape;
  className?: string;
  stroke?: string;
  fill?: string;
  w?: number;
  h?: number;
  layers?: number;
}) {
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width={w} height={h} className={className} aria-hidden>
      <ShapeSvg shape={shape} w={w} h={h} unit={0.36} stroke={stroke} fill={fill} strokeWidth={1.25} layers={layers} />
    </svg>
  );
}
