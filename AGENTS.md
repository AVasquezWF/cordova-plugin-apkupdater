# Repository Guidelines

## Project Structure & Module Organization
- Cordova Android-only plugin; Java sources live under `src/android/` with the `de/kolbasa/apkupdater` package tree.
- JS bridge files are in `www/` (`ApkUpdater.js`, `API.js`); plugin metadata and permissions live in `plugin.xml`.
- Ionic/TypeScript wrapper and typings live in `src/ionic/` and compile to `src/ionic/index.js`.
- Documentation lives in `README.md` and `doc/`.

## Build, Test, and Development Commands
- `npm install` installs TypeScript tooling.
- `npm run build` runs `tsc -p src/ionic` to refresh the Ionic wrapper output.
- No automated tests are configured.

## Cordova & Android Packaging
- The plugin is built by a host Cordova Android app; keep `plugin.xml` in sync when adding Java sources, XML resources, permissions, or features.
- Android XML resources live in `src/android/xml/` and are referenced from `plugin.xml`.

## Coding Style & Naming Conventions
- Keep Java classes under `de.kolbasa.apkupdater` subpackages aligned with the `plugin.xml` `source-file` entries.
- Treat `www/` as the stable JS bridge API; update docs when public methods change.
- After editing `src/ionic/index.ts`, run `npm run build` to regenerate `src/ionic/index.js`.

## Commit & Pull Request Guidelines
- Keep commits scoped; avoid committing generated artifacts unless intentionally updating `src/ionic/index.js` or `www/`.
- PRs should mention the Android-only scope, link issues, and note whether `npm run build` was run.
- Commits should have a title and description and have 50-80 words, description is writen with bulletpoints and dont uses file paths
