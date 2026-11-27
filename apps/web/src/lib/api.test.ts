import { describe, expect, it } from "vitest";
import { ApiError, isRevisionConflict } from "./api";

describe("a refused save", () => {
  it("is a revision conflict when another save got there first", () => {
    const conflict = new ApiError("Project was modified elsewhere (revision 3, you had 2). Reload before saving.", 409);

    expect(isRevisionConflict(conflict)).toBe(true);
  });

  it("is not one when the workspace stores its quota", () => {
    const full = new ApiError(
      "This workspace stores as much as it may (100 MB): delete runs, projects or share links first.",
      409,
    );

    expect(isRevisionConflict(full)).toBe(false);
  });

  it("is not one for any other failure", () => {
    expect(isRevisionConflict(new ApiError("Project 'p1' not found", 404))).toBe(false);
    expect(isRevisionConflict(new Error("Project was modified elsewhere"))).toBe(false);
  });
});
