# Code Style

## General
* 2-space indentation
* No semicolons

## SQL (DB)
* Lower-case keywords
* Table columns names in camelCase, matching data class fields (so that no transformation needed)
* Changesets in `db` follow table-per-file structure

## Svelte/TypeScript (UI)
* Single-quote strings
* Generated `./src/api/types.ts` from Kotlin data classes and enums
* No padding spaces inside `{}`
* Real functions preferred to const lambdas
* HTML standard tags without closing slash (img, br, meta, etc)
* In Svelte components, no space before closing slash `/>`
* Svelte 4 simple syntax (no runes), use event handlers without colons

## Kotlin (Backend)
* No spaces before `:` in type declarations and extends/implements
* Single short annotations on the same line, e.g. `@Test fun test()`
* Prefer expression body functions if possible
* Import enum constants, avoiding prefixing them with type name
* In tests, prefer using pre-created entities from `TestData`; modify only needed fields using .copy()
* For route/service layers tests, extend `BaseMocks` to avoid duplicating mock creation and basic setup
