# Changelog

All notable changes to this project will be documented in this file.

## [v1.1.0] - 2026-01-30

### Added
- **Smart Step Strategy**: Added `smartStep` parameter to stepping tools (`debug_step_over`, `debug_step_into`, etc.). When enabled (`true`), it automatically selects the last suspended thread or a non-system thread if `threadName` is not provided.
- **JDK 7 Support**: Introduced `jdb-mcp-jdk7` module to support debugging legacy Java 7 applications.
- **Multi-module Architecture**: Refactored the project into `jdb-mcp-core`, `jdb-mcp-jdk17`, and `jdb-mcp-jdk7` to handle cross-version compatibility.
- **Release Directory**: Automated generation of fat JARs into the `release/` directory during the build process.

### Changed
- **Step Over Stability**: Improved `step_over` handling to prevent timeouts when a step operation hits a breakpoint simultaneously.
- **Thread Selection Logic**: `debug_step` tools now enforce explicit thread selection (via `threadName`) or explicit opt-in for automation (via `smartStep: true`) to prevent accidental operations on wrong threads.
- **Documentation**: Updated `PARAMETER_GUIDE.md` and `README.md` with new build instructions and parameter usage.

### Fixed
- Fixed an issue where `step_over` would time out if the step completed by hitting a breakpoint event instead of a step event.
