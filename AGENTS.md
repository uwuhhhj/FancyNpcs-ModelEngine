# Agent Requirements

## Code Style

- Declare types explicitly; do not use `var`. Minimize `Optional` usage.
- Always use braces for control structures. Keep braces and logic on separate lines, and leave at least one blank line between methods.
- Qualify instance fields with `this.`; use `super.` when explicitly accessing superclass fields.
- Do not add comments unless requested, except for core logic in complex methods, which should be explained with `//` comments.
- Use parentheses around nested ternary expressions in complex ternary operations to improve readability.
- When dependencies can be introduced directly, try as much as possible to avoid using reflection for integration. Even if reflection is used, it should be optimized by caching certain reflection objects in advance.
- Minimize splitting it into a large number of small methods in order to improve readability.

## Build and Validation

- Follow `CONTRIBUTING.md` and the build instructions in `README.md`.
- Preserve the retained upstream NPC and packets version modules.
- Run `:plugins:fancynpcs-model:test` and `:plugins:fancynpcs-model:shadowJar` after changing the model addon.
- Keep version metadata and release documentation consistent with `plugins/fancynpcs-model/VERSION`.
- Keep historical runtime validation evidence intact; report current build and test evidence separately when no new server validation has been performed.
