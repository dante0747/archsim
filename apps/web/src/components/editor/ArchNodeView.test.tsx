import { type NodeProps, ReactFlowProvider } from "@xyflow/react";
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { ArchNode } from "@/lib/document";
import type { Catalog, NodeConfig } from "@/lib/types";
import catalogJson from "@/test/fixtures/catalog.json";
import { BUCKET_S, fixtureNode, fixtureNodes, fixtureReport } from "@/test/fixtures/dashboard-report";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { ArchNodeView } from "./ArchNodeView";

const AUTOSCALED_POD = { platform: "kubernetes", replicas: 3, autoscaling: true, minReplicas: 3, maxReplicas: 14 };

/** The fixture run's "api" part as a service card on the canvas. */
function showApi(config: NodeConfig) {
  const props = { id: "api", data: { type: "service", label: "Api", config }, selected: false } as unknown as NodeProps<ArchNode>;
  return render(
    <ReactFlowProvider>
      <ArchNodeView {...props} />
    </ReactFlowProvider>,
  );
}

const replayAt = (bucket: number) => act(() => useUi.setState({ playhead: bucket * BUCKET_S }));

describe("ArchNodeView", () => {
  beforeEach(() => {
    useEditor.setState({ catalog: catalogJson as unknown as Catalog, nodes: [], issues: [], analysis: null });
    useRuns.setState({ reports: {}, activeRunId: null, overlay: true });
    useUi.setState({ playhead: null, playing: false });
  });
  afterEach(cleanup);

  it("replays the pods the autoscaler ran when the design has no cluster part", () => {
    // the fixture's api scaled from 3 to 5 replicas by its seventh bucket and, on no cluster part,
    // recorded no pod series of its own
    useRuns.setState({ reports: { r1: fixtureReport() }, activeRunId: "r1" });
    showApi(AUTOSCALED_POD);

    replayAt(6);

    expect(screen.getByLabelText("Pods: 5 ready")).toBeInTheDocument();
  });

  it("replays a cluster's own pod series, starting and Pending pods included", () => {
    const nodes = fixtureNodes().map((n) =>
      n.id === "api"
        ? fixtureNode("api", "service", "compute", { ...n.timeseries, podsReady: Array(20).fill(4), podsStarting: Array(20).fill(1), podsPending: Array(20).fill(2) })
        : n,
    );
    useRuns.setState({ reports: { r1: fixtureReport(nodes) }, activeRunId: "r1" });
    showApi(AUTOSCALED_POD);

    replayAt(6);

    expect(screen.getByLabelText("Pods: 4 ready, 1 starting, 2 Pending")).toBeInTheDocument();
  });

  it("shows an autoscaled service's replica range in the design", () => {
    showApi(AUTOSCALED_POD);

    expect(screen.getByText("Autoscaling").nextSibling).toHaveTextContent("3–14 replicas");
    expect(screen.queryByText("Replicas")).not.toBeInTheDocument();
  });

  it("shows a pinned autoscaler's one count rather than a range", () => {
    const { unmount } = showApi({ ...AUTOSCALED_POD, minReplicas: 4, maxReplicas: 4 });
    expect(screen.getByText("Autoscaling").nextSibling).toHaveTextContent(/^4 replicas$/);
    unmount();

    showApi({ ...AUTOSCALED_POD, replicas: 1, minReplicas: 1, maxReplicas: 1 });

    expect(screen.getByText("Autoscaling").nextSibling).toHaveTextContent(/^1 replica$/);
  });

  it("keeps a service's fixed replicas in the design", () => {
    showApi({ platform: "kubernetes", replicas: 3, autoscaling: false });

    expect(screen.getByText("Replicas").nextSibling).toHaveTextContent("3");
    expect(screen.queryByText("Autoscaling")).not.toBeInTheDocument();
  });

  it("leaves out the replica range of a serverless function, which does not autoscale", () => {
    showApi({ platform: "serverless", replicas: 3, autoscaling: true, minReplicas: 3, maxReplicas: 14 });

    expect(screen.queryByText("Autoscaling")).not.toBeInTheDocument();
  });
});
