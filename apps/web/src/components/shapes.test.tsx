import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Kind } from "@/lib/theme";
import { SHAPE_OF, ShapeGlyph, ShapeSvg, type Shape } from "./shapes";

const kinds: Kind[] = ["traffic", "network", "compute", "cache", "database", "messaging", "external", "other"];

describe("schematic shapes", () => {
  it("gives every kind a distinct shape", () => {
    const shapes = kinds.map((k) => SHAPE_OF[k]);
    expect(new Set(shapes).size).toBe(kinds.length);
  });

  it.each(Object.values(SHAPE_OF) as Shape[])("draws %s inside its box", (shape) => {
    const { container } = render(
      <svg>
        <ShapeSvg shape={shape} w={232} h={110} />
      </svg>,
    );
    const marks = container.querySelectorAll("path, rect, line, circle");
    expect(marks.length).toBeGreaterThan(0);
    for (const d of [...container.querySelectorAll("path")].map((p) => p.getAttribute("d") ?? "")) {
      expect(d).not.toMatch(/NaN|Infinity/);
    }
  });

  it("stacks one layer per replica, up to three", () => {
    const layers = (n: number) =>
      render(
        <svg>
          <ShapeSvg shape="stack" w={232} h={110} layers={n} />
        </svg>,
      ).container.querySelectorAll("rect").length;
    expect(layers(1)).toBe(1);
    expect(layers(2)).toBe(2);
    expect(layers(8)).toBe(3);
  });

  it.each(["pipe", "cloud"] as Shape[])("keeps %s finite at glyph size", (shape) => {
    const { container } = render(<ShapeGlyph shape={shape} />);
    for (const d of [...container.querySelectorAll("path")].map((p) => p.getAttribute("d") ?? "")) {
      expect(d).not.toMatch(/NaN|Infinity/);
    }
  });

  it("renders small glyphs as decorative", () => {
    const { container } = render(<ShapeGlyph shape="cylinder" />);
    expect(container.querySelector("svg")?.getAttribute("aria-hidden")).toBe("true");
  });
});
