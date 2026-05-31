# GitHub Actions Scope (restricted)

GitHub Actions is **not** used for final Termux release packaging.

## Allowed in CI

- armv7 prebuild handoff artifact generation (`.github/workflows/prebuild-armv7.yml`)
- metadata/checksum/template generation for downstream local packaging

## Not allowed in CI

- claiming final Termux runtime compatibility
- publishing final deb/pkg artifacts as production-ready for Termux

## Final package authority

Final runtime wrapping, launcher integration, package build, and runtime/plugin validation must run locally on real Termux devices.
