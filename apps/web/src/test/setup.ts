import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Testing Library unmounts on its own only where tests run with globals. Without them a render
// outlives its test, and React's scheduler can still be working when the file's jsdom closes
// ("window is not defined", seen on CI's Linux runner), so every test unmounts what it rendered.
afterEach(() => {
  cleanup();
});
