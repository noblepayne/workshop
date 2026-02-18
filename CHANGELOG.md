# Changelog

## [Unreleased]

### Added
- **Nix flake** with separate `workshop-server` and `workshop-client` packages
- **NixOS module** (`services.workshop`) for easy systemd service deployment
- **Overlay** for adding workshop to `environment.systemPackages`

### Fixed
- **ProtectHome** changed from `true` to `"read-only"` to allow maven/cache access in `/var/lib/workshop`
- Added **XDG_CACHE_HOME** environment variable to redirect maven cache

### Changed
- Split into two packages: server (full service) and client (library only)

## [0.1.0] - 2024-02-20

Initial release. Single babashka script with:
- Named channels with typed messages
- Task queue (open → claimed → done/abandon)
- Content-addressed file storage
- SSE feeds for real-time updates
- Browser terminal UI
- SQLite persistence
