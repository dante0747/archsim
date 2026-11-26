import { fireEvent, render, screen } from "@testing-library/react";
import { Tooltip } from "radix-ui";
import { describe, expect, it, vi } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import type { Catalog, FieldDescriptor } from "@/lib/types";
import { FieldControl, FieldGroups, describeValue, helpText, validateNumber } from "./FieldControl";

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

  it("edits a service time by load as req/s: factor pairs (schema 1.6)", () => {
    const onChange = vi.fn();
    const f = field(serviceFields, "serviceTimeByLoad");
    expect(f.kind).toBe("loadCurve");
    const value = [
      { rps: 50, factor: 3.6 },
      { rps: 1674, factor: 1 },
    ];
    wrap(<FieldControl field={f} value={value} onChange={onChange} idPrefix="t" />);
    const input = screen.getByLabelText("Service time by load");
    expect(input).toHaveValue("50: 3.6, 1674: 1");
    expect(screen.getByText("×3.6 at 50 req/s to ×1 at 1,674 req/s")).toBeInTheDocument();
    // a pair that does not read is flagged and not saved
    fireEvent.change(input, { target: { value: "50: 3.6, 1674" } });
    fireEvent.blur(input);
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("req/s: factor pairs");
    fireEvent.change(input, { target: { value: "50: 3, 500: 1.5, 1674: 1" } });
    fireEvent.blur(input);
    expect(onChange).toHaveBeenLastCalledWith([
      { rps: 50, factor: 3 },
      { rps: 500, factor: 1.5 },
      { rps: 1674, factor: 1 },
    ]);
    expect(describeValue(f, value)).toBe("×3.6 at 50 req/s to ×1 at 1,674 req/s");
  });

  it("shows an empty setting as what the hint says in full, and starts an edit from its value", () => {
    const onChange = vi.fn();
    const auto = { value: 0.6, source: "the last analysis", text: "0.6 (hyperthread pairs, the default)" };
    wrap(<FieldControl field={field(serviceFields, "siblingSpeed")} value={null} onChange={onChange} idPrefix="t" auto={auto} />);

    fireEvent.click(screen.getByLabelText("Hyperthread sibling speed: Auto"));

    expect(screen.getByText("0.6 (hyperthread pairs, the default)")).toBeInTheDocument();
    expect(onChange).toHaveBeenCalledWith(0.6);
  });

  it("says what it is in one line under its label, without the unit the field shows", () => {
    wrap(<FieldControl field={field(serviceFields, "serviceTimeMs")} value={{ kind: "lognormal", median: 8, p95: 25 }} onChange={() => {}} idPrefix="t" />);

    expect(screen.getByText("Handler time per request on a worker, excluding downstream calls.")).toBeInTheDocument();
    expect(screen.getByText(/mean 10\.2 ms/)).toBeInTheDocument();
  });

  it("keeps an abbreviation's full stop inside the help line's sentence", () => {
    expect(helpText({ description: "Copies of each item kept by a leaderless or consensus store (e.g. Cassandra, distributed SQL). Others ignore it." })).toBe(
      "Copies of each item kept by a leaderless or consensus store (e.g. Cassandra, distributed SQL).",
    );
    expect(helpText({ description: "fixed_ratio: each read hits. ttl_keyspace: keys expire." })).toBeNull();
  });

  it("says where its value comes from: a default, an estimate, vendor docs or a measurement", () => {
    const serviceTime = field(serviceFields, "serviceTimeMs");
    const value = { kind: "lognormal", median: 8, p95: 25 };
    const { rerender } = wrap(<FieldControl field={serviceTime} value={value} onChange={() => {}} idPrefix="t" source={{ kind: "illustrative", source: "ArchSim default" }} />);
    expect(screen.getByTestId("input-source")).toHaveTextContent("default");

    rerender(
      <Tooltip.Provider>
        <FieldControl field={serviceTime} value={value} onChange={() => {}} idPrefix="t" source={{ kind: "assumed" }} />
      </Tooltip.Provider>,
    );
    expect(screen.getByTestId("input-source")).toHaveTextContent("your estimate");

    rerender(
      <Tooltip.Provider>
        <FieldControl field={serviceTime} value={value} onChange={() => {}} idPrefix="t" source={{ kind: "documented", source: "https://www.postgresql.org/docs/16/" }} />
      </Tooltip.Provider>,
    );
    expect(screen.getByRole("link", { name: /documented · postgresql\.org/ })).toHaveAttribute("href", "https://www.postgresql.org/docs/16/");

    rerender(
      <Tooltip.Provider>
        <FieldControl field={serviceTime} value={value} onChange={() => {}} idPrefix="t" source={{ kind: "measured", source: "Prometheus" }} />
      </Tooltip.Provider>,
    );
    expect(screen.getByLabelText("measured: Prometheus")).toBeInTheDocument();
  });

  it("offers to reset a value set in the design to the part's default, and only then", () => {
    const onChange = vi.fn();
    const { rerender } = wrap(<FieldControl field={field(serviceFields, "replicas")} value={5} onChange={onChange} idPrefix="t" />);

    fireEvent.click(screen.getByRole("button", { name: "Reset Replicas to the default, 2" }));

    expect(onChange).toHaveBeenCalledWith(2);
    rerender(
      <Tooltip.Provider>
        <FieldControl field={field(serviceFields, "replicas")} value={2} onChange={onChange} idPrefix="t" />
      </Tooltip.Provider>,
    );
    expect(screen.queryByRole("button", { name: /Reset Replicas/ })).toBeNull();
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
