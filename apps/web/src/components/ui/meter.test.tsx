import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { Meter } from "./meter";

const colours = (el: HTMLElement) => [...el.querySelectorAll("span")].map((s) => s.style.background);

describe("Meter", () => {
  afterEach(cleanup);

  it("lights segments up to the level, green first", () => {
    const { container } = render(<Meter level={0.5} />);
    const c = colours(container.firstElementChild as HTMLElement);
    expect(c).toHaveLength(12);
    expect(c.filter((x) => x === "var(--status-good)")).toHaveLength(6);
    expect(c.filter((x) => x === "var(--surface-3)")).toHaveLength(6);
  });

  it("turns yellow then red near saturation", () => {
    const { container } = render(<Meter level={1} />);
    const c = colours(container.firstElementChild as HTMLElement);
    expect(c.slice(0, 7).every((x) => x === "var(--status-good)")).toBe(true);
    expect(c.slice(7, 10).every((x) => x === "var(--status-warning)")).toBe(true);
    expect(c.slice(10).every((x) => x === "var(--status-critical)")).toBe(true);
  });

  it("clamps levels outside 0..1", () => {
    const { container } = render(
      <>
        <Meter level={-0.4} />
        <Meter level={3} />
      </>,
    );
    const [low, high] = [...container.children] as HTMLElement[];
    expect(colours(low).every((x) => x === "var(--surface-3)")).toBe(true);
    expect(colours(high).every((x) => x !== "var(--surface-3)")).toBe(true);
  });
});
