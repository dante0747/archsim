import * as React from "react";
import { cn } from "@/lib/utils";

export const inputClass =
  "h-8 w-full min-w-0 rounded-md border border-line bg-surface-2 px-2.5 text-sm text-ink placeholder:text-ink-3 transition-colors hover:border-line-strong focus-visible:border-accent focus-visible:bg-surface focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/20 disabled:opacity-50 aria-[invalid=true]:border-critical";

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => <input ref={ref} className={cn(inputClass, className)} {...props} />,
);
Input.displayName = "Input";

export const Textarea = React.forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement>
>(({ className, ...props }, ref) => (
  <textarea ref={ref} className={cn(inputClass, "h-auto min-h-16 py-1.5", className)} {...props} />
));
Textarea.displayName = "Textarea";

export const NativeSelect = React.forwardRef<
  HTMLSelectElement,
  React.SelectHTMLAttributes<HTMLSelectElement>
>(({ className, children, ...props }, ref) => (
  <select ref={ref} className={cn(inputClass, "cursor-pointer pr-6", className)} {...props}>
    {children}
  </select>
));
NativeSelect.displayName = "NativeSelect";

export function Label({ className, ...props }: React.LabelHTMLAttributes<HTMLLabelElement>) {
  return <label className={cn("text-xs font-medium text-ink-2", className)} {...props} />;
}
