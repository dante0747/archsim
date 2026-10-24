"use client";

import { Toaster as Sonner } from "sonner";

export function Toaster() {
  return (
    <Sonner
      position="bottom-right"
      closeButton
      toastOptions={{
        classNames: {
          toast: "!bg-surface !text-ink !border-line",
          description: "!text-ink-2",
        },
      }}
    />
  );
}
