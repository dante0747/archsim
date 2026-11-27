import { afterEach, describe, expect, it, vi } from "vitest";
import { guardLink, leave, setLeaveGuard } from "./leave";

describe("leaving a page that may hold unsaved work", () => {
  afterEach(() => setLeaveGuard(null));

  it("leave_withNoGuard_goesAtOnce", () => {
    const go = vi.fn();

    leave(go);

    expect(go).toHaveBeenCalledTimes(1);
  });

  it("leave_withAGuardThatHasNothingToKeep_goesAtOnce", () => {
    const go = vi.fn();
    setLeaveGuard(() => true);

    leave(go);

    expect(go).toHaveBeenCalledTimes(1);
  });

  it("leave_withAGuardThatHoldsItBack_leavesTheMoveToTheGuard", () => {
    const go = vi.fn();
    let held: (() => void) | null = null;
    setLeaveGuard((move) => {
      held = move;
      return false;
    });

    leave(go);

    expect(go).not.toHaveBeenCalled();
    held!();
    expect(go).toHaveBeenCalledTimes(1);
  });

  it("guardLink_withNoGuardOrNothingToKeep_letsTheLinkNavigate", () => {
    const event = { preventDefault: vi.fn() };
    const go = vi.fn();

    guardLink(event, go);
    setLeaveGuard(() => true);
    guardLink(event, go);

    expect(event.preventDefault).not.toHaveBeenCalled();
    expect(go).not.toHaveBeenCalled();
  });

  it("guardLink_withAGuardThatHoldsItBack_stopsTheLinkAndHandsTheGuardTheSameMove", () => {
    const event = { preventDefault: vi.fn() };
    const go = vi.fn();
    const guard = vi.fn(() => false);
    setLeaveGuard(guard);

    guardLink(event, go);

    expect(event.preventDefault).toHaveBeenCalledTimes(1);
    expect(guard).toHaveBeenCalledWith(go);
    expect(go).not.toHaveBeenCalled();
  });
});
