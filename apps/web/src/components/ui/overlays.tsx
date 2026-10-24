import { Dialog as RDialog, DropdownMenu as RMenu, Tabs as RTabs } from "radix-ui";
import { X } from "lucide-react";
import * as React from "react";
import { cn } from "@/lib/utils";

// ---- Tabs -------------------------------------------------------------------------------------

export const Tabs = RTabs.Root;
export const TabsContent = RTabs.Content;

export function TabsList({ className, ...props }: React.ComponentProps<typeof RTabs.List>) {
  return <RTabs.List className={cn("flex items-center gap-0.5 border-b border-line", className)} {...props} />;
}

export function TabsTrigger({ className, ...props }: React.ComponentProps<typeof RTabs.Trigger>) {
  return (
    <RTabs.Trigger
      className={cn(
        "relative -mb-px inline-flex h-10 items-center gap-1.5 whitespace-nowrap px-2.5 font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-ink-3 transition-colors hover:text-ink data-[state=active]:text-ink [&_svg]:size-3.5 after:absolute after:inset-x-2 after:bottom-0 after:h-px after:bg-transparent data-[state=active]:after:bg-accent",
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
      <RDialog.Overlay className="fixed inset-0 z-50 bg-black/55" />
      <RDialog.Content
        className={cn(
          "fixed left-1/2 top-1/2 z-50 max-h-[85vh] w-[calc(100vw-32px)] max-w-lg -translate-x-1/2 -translate-y-1/2 overflow-auto rounded-lg border border-line bg-surface p-6 shadow-float",
          className,
        )}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <RDialog.Title className="text-base font-semibold text-ink">{title}</RDialog.Title>
            {description ? (
              <RDialog.Description className="mt-1 text-sm text-ink-2">{description}</RDialog.Description>
            ) : (
              <RDialog.Description className="sr-only">{title}</RDialog.Description>
            )}
          </div>
          <RDialog.Close className="rounded p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Close">
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
        className={cn("z-50 min-w-52 rounded-lg border border-line bg-surface p-1 shadow-float", className)}
        {...props}
      />
    </RMenu.Portal>
  );
}

export function MenuItem({ className, ...props }: React.ComponentProps<typeof RMenu.Item>) {
  return (
    <RMenu.Item
      className={cn(
        "flex cursor-default select-none items-center gap-2 rounded-md px-2 py-1.5 text-sm text-ink outline-none data-[disabled]:opacity-50 data-[highlighted]:bg-surface-2 [&_svg]:size-4 [&_svg]:text-ink-3",
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
