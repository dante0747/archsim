import type { Metadata } from "next";
import { AccuracyPage } from "@/components/accuracy/AccuracyPage";
import { accuracy, minus } from "@/lib/accuracy";

export const metadata: Metadata = {
  title: "How accurate is ArchSim? — ArchSim",
  description: `ArchSim's predictions checked against real systems: capacity knee errors from ${minus(accuracy.summary.kneeErrorRangeText)}, every run with its date and machine, and how to calibrate your own design.`,
};

export default function Accuracy() {
  return <AccuracyPage data={accuracy} />;
}
