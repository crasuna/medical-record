# ADR 0003: Keep one module with package boundaries and Navigation 3 scenes

- Status: Accepted
- Date: 2026-08-08

## Decision

Keep one Gradle module, `:app`, with source boundaries under `app`, `core`, and `feature`. Screens
call ViewModels, which call repositories, which call Room, encrypted attachment storage, or small
Android system adapters. Simple actions do not receive UseCase wrappers, and no third-party MVI or
full Clean Architecture layer is introduced.

Use Compose Material 3, serializable Navigation 3 keys, and `NavigationSuiteScaffold`. Compact
windows use a bottom bar; medium and expanded windows use a rail. Encounter and medication
list/detail routes opt into a stable custom Navigation 3 `SceneStrategy` at the medium-width
breakpoint. The project does not depend on the release-candidate `adaptive-navigation3` artifact and
does not use `ListDetailPaneScaffold`.

## Consequences

- Phone and wide-window navigation share one back stack and destination model.
- Package boundaries remain visible without empty Gradle modules or speculative abstractions.
- The custom scene must be covered by layout/metadata tests and device checks, including foldable
  partitions and resizable desktop windows.
