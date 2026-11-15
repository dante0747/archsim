import type { Metadata } from "next";
import { AccuracyPage } from "@/components/accuracy/AccuracyPage";
import { accuracy, minus } from "@/lib/accuracy";
import { pageMetadata } from "@/lib/site/site";

export const metadata: Metadata = pageMetadata({
  title: "How accurate is ArchSim?",
  description: `ArchSim's predictions checked against real systems: capacity knee errors from ${minus(accuracy.summary.kneeErrorRangeText)}, every run with its date and machine, and how to calibrate your own design.`,
  path: "/accuracy/",
});

/** The app's accuracy page, inside the website's header and footer. */
export default function Accuracy() {
  return <AccuracyPage data={accuracy} header={null} footer={null} />;
}
