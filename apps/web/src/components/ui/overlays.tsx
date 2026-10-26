import { Dialog as RDialog, DropdownMenu as RMenu, Tabs as RTabs } from "radix-ui";
import { X } from "lucide-react";
import * as React from "react";
import { cn } from "@/lib/utils";

// ---- Tabs -------------------------------------------------------------------------------------

export const Tabs = RTabs.Root;
export const TabsContent = RTabs.Content;

export function TabsList({ className, ...props }: React.ComponentProps<typeof RTabs.List>) {
  // A segmented control, like the tabs of a pro app's inspector.
  return <RTabs.List className={cn("m-2.5 flex items-center gap-0.5 rounded-[10px] bg-[color:var(--seg)] p-[3px]", className)} {...props} />;
}

export function TabsTrigger({ className, ...props }: React.ComponentProps<typeof RTabs.Trigger>) {
  return (
    <RTabs.Trigger
      className={cn(
        "inline-flex h-7 flex-1 items-center justify-center gap-1.5 whitespace-nowrap rounded-[7px] px-2.5 text-[12.5px] font-medium text-ink-3 transition-colors hover:text-ink data-[state=active]:bg-[color:var(--seg-on)] data-[state=active]:text-ink data-[state=active]:shadow-[0_1px_2px_rgba(0,0,0,0.18),inset_0_1px_0_var(--highlight)] [&_svg]:size-3.5 [&_svg]:opacity-80",
        className,
      )}
      {...props}
    />
  );
}

// ---- Dialog -----------------------------------------------------------------------------------

export const Dialog = RDialog.Root;
export const DialogTrigger = RDialog.Trigger;
export const DialogClose = RDialog.Close;

export function DialogContent({
  title,
  description,
  children,
  className,
}: {
  title: string;
  description?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <RDialog.Portal>
      <RDialog.Overlay className="fixed inset-0 z-50 bg-black/45 backdrop-blur-[3px]" />
      <RDialog.Content
        className={cn(
          "surface-sheen fixed left-1/2 top-1/2 z-50 max-h-[85vh] w-[calc(100vw-32px)] max-w-lg -translate-x-1/2 -translate-y-1/2 overflow-auto rounded-xl border border-line-strong bg-surface p-6 shadow-float",
          className,
        )}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <RDialog.Title className="font-display text-[24px] leading-tight text-ink">{title}</RDialog.Title>
            {description ? (
              <RDialog.Description className="mt-1 text-sm text-ink-2">{description}</RDialog.Description>
            ) : (
              <RDialog.Description className="sr-only">{title}</RDialog.Description>
            )}
          </div>
          <RDialog.Close className="rounded-md p-1 text-ink-3 transition-colors hover:bg-surface-2 hover:text-ink" aria-label="Close">
            <X className="size-4" />
          </RDialog.Close>
        </div>
        {children}
      </RDialog.Content>
    </RDialog.Portal>
  );
}

// ---- Dropdown menu ----------------------------------------------------------------------------

export const Menu = RMenu.Root;
export const MenuTrigger = RMenu.Trigger;

export function MenuContent({ className, ...props }: React.ComponentProps<typeof RMenu.Content>) {
  return (
    <RMenu.Portal>
      <RMenu.Content
        sideOffset={6}
        align="end"
        className={cn("surface-glass z-50 min-w-52 rounded-lg p-1", className)}
        {...props}
      />
    </RMenu.Portal>
  );
}

export function MenuItem({ className, ...props }: React.ComponentProps<typeof RMenu.Item>) {
  return (
    <RMenu.Item
      className={cn(
        "flex cursor-default select-none items-center gap-2 rounded-md px-2 py-1.5 text-[13px] text-ink outline-none transition-colors data-[disabled]:opacity-50 data-[highlighted]:bg-accent-soft [&_svg]:size-4 [&_svg]:text-ink-3",
        className,
      )}
      {...props}
    />
  );
}

export function MenuSeparator() {
  return <RMenu.Separator className="my-1 h-px bg-line" />;
}

export function MenuLabel({ className, ...props }: React.ComponentProps<typeof RMenu.Label>) {
  return <RMenu.Label className={cn("microlabel px-2 py-1", className)} {...props} />;
}
