# Phase 24 — Deferred items (out-of-scope discoveries)

Items logged per GSD executor scope-boundary rule: discovered during plan execution but NOT fixed
because they are unrelated to the current task.

## Plan 24-03

- **`backend/target/` is tracked in git but should be gitignored.** Running `mvn test` during
  24-03 touched ~120 `.class` and `surefire-reports/` files that show up as modified/untracked.
  The root `.gitignore` only ignores `tools/osm2streets-cli/target/`. Pre-existing since Phase 09
  commit `2e4bfeb`. Recommend a dedicated cleanup plan: add `backend/target/` to `.gitignore`,
  `git rm -r --cached backend/target/`, commit. Out of scope for 24-03 because the fix would
  rewrite ~700 tracked binary files unrelated to osm2streets.
