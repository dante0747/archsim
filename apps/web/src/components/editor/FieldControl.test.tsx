import { fireEvent, render, screen } from "@testing-library/react";
import { Tooltip } from "radix-ui";
import { describe, expect, it, vi } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import type { Catalog, FieldDescriptor } from "@/lib/types";
import { FieldControl, FieldGroups, describeValue, validateNumber } from "./FieldControl";

const catalog = catalogJson as unknown as Catalog;
const serviceFields = catalog.components.find((c) => c.type === "service")!.fields;
const cacheFields = catalog.components.find((c) => c.type === "cache")!.fields;
const field = (fields: FieldDescriptor[], key: string) => fields.find((f) => f.key === key)!;

function wrap(ui: React.ReactElement) {
  return render(<Tooltip.Provider>{ui}</Tooltip.Provider>);
}

describe("FieldControl", () => {
  it("commits only valid numbers and shows range errors", () => {
    const onChange = vi.fn();
    wrap(<FieldControl field={field(serviceFields, "replicas")} value={2} onChange={onChange} idPrefix="t" />);
    const input = screen.getByLabelText("Replicas");
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "0" } });
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("≥ 1");
    fireEvent.change(input, { target: { value: "3" } });
    expect(onChange).toHaveBeenLastCalledWith(3);
  });

  it("supports nullable fields with a 'No limit' toggle", () => {
    const onChange = vi.fn();
    wrap(<FieldControl field={field(serviceFields, "queueTimeoutMs")} value={null} onChange={onChange} idPrefix="t" />);
    const box = screen.getByLabelText("Queue timeout: No limit");
    expect(box).toBeChecked();
    fireEvent.click(box);
    expect(onChange).toHaveBeenCalledWith(1000);
  });

  it("marks assumptions", () => {
    wrap(<FieldControl field={field(serviceFields, "serviceTimeMs")} value={{ kind: "lognormal", median: 8, p95: 25 }} onChange={() => {}} idPrefix="t" />);
    expect(screen.getByText("assumption")).toBeInTheDocument();
    expect(screen.getByText(/mean 10\.2 ms/)).toBeInTheDocument();
  });

  it("hides fields whose visibleWhen does not match", () => {
    wrap(<FieldGroups fields={cacheFields} values={{ hitModel: "fixed_ratio" }} onChange={() => {}} idPrefix="c" />);
    expect(screen.getByText("Hit ratio")).toBeInTheDocument();
    expect(screen.queryByText("TTL")).not.toBeInTheDocument();
  });

  it("validates numbers against descriptor constraints", () => {
    expect(validateNumber({ kind: "integer", min: 1, max: 10 }, 2.5)).toMatch(/whole/);
    expect(validateNumber({ kind: "number", min: 0, exclusiveMin: true }, 0)).toMatch(/> 0/);
    expect(validateNumber({ kind: "number", max: 5 }, 6)).toMatch(/≤ 5/);
    expect(validateNumber({ kind: "number", min: 0 }, 3)).toBeNull();
  });

  it("describes values for node summaries", () => {
    expect(describeValue(field(cacheFields, "hitRatio"), 0.8)).toBe("80%");
    expect(describeValue(field(serviceFields, "queueTimeoutMs"), null)).toBe("no limit");
    expect(describeValue(undefined, "least_connections")).toBe("Least connections");
  });
});
