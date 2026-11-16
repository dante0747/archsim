import { describe, expect, it } from "vitest";
import { privacyUrl } from "./privacy";

describe("the hosted server's privacy notice (ARCHSIM_PRIVACY_URL)", () => {
  it("is the address the setting names", () => {
    expect(privacyUrl({ ARCHSIM_PRIVACY_URL: " https://archsim.io/privacy/ " })).toBe("https://archsim.io/privacy/");
  });

  it("is none when the setting is unset or empty", () => {
    expect(privacyUrl({})).toBeNull();
    expect(privacyUrl({ ARCHSIM_PRIVACY_URL: "" })).toBeNull();
  });

  it("is none when the setting is not an http or https address", () => {
    expect(privacyUrl({ ARCHSIM_PRIVACY_URL: "javascript:alert(1)" })).toBeNull();
    expect(privacyUrl({ ARCHSIM_PRIVACY_URL: "not a url" })).toBeNull();
  });
});
