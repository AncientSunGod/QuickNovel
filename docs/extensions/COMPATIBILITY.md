# QuickNovel extension compatibility

## Current status

QuickNovel can add a repository manifest, display its entries, download a bundle to private app storage, update it, and uninstall it. This is the **catalogue and package-management layer** only. A downloaded LNReader plugin is not yet a working QuickNovel provider.

## Why a compatibility adapter is required

QuickNovel's native provider contract is the Kotlin `MainAPI` interface:
- `search(query)` returns `List<SearchResponse>?`
- `load(url)` returns a `LoadResponse?` (usually a `StreamResponse` with chapter entries)
- `loadHtml(url)` returns chapter content
- optional main-page methods return `HeadMainPageResponse`

LNReader plugins commonly use a different JavaScript API and bundled module imports. The QuickJS engine does not automatically supply React Native, Metro module resolution, LNReader helper libraries, Cheerio, or QuickNovel's HTTP client. Therefore a bundle passing a JavaScript smoke test does not establish compatibility.

## Proposed adapter boundary

A provider adapter must translate the following operations to QuickNovel's native model:

1. Search: input query; output entries containing a title, canonical URL, and optional poster URL.
2. Novel details: input canonical URL; output metadata plus an ordered chapter list.
3. Chapter content: input chapter URL; output readable HTML/text.
4. Networking: `ExtensionHttpClient` now provides a host-owned HTTP GET helper with bounded response sizes, connect/read timeouts, redirect limits, and no access to Android objects. It is not exposed to JavaScript yet; the eventual bridge must add call budgets and privacy controls.
5. Parsing: a defined HTML parsing API rather than pretending browser DOM or Cheerio exists.
6. Errors: return structured errors; never crash the app when a plugin fails.

The JavaScript side should expose a versioned, documented contract rather than attempting to execute arbitrary React Native bundles without their runtime. The host must invoke it on a worker thread and enforce a hard execution budget before enabling third-party code.

## Security requirements before runtime activation

- Do not run downloaded code on the UI thread.
- Do not expose Android `Context`, files, reflection, app preferences, or Kotlin objects to a plugin.
- Bound bundle and network response sizes; use timeouts and redirect limits.
- Do not treat syntax success or exported names as a security review.
- Make install/update atomic so a failed download cannot destroy the last working bundle.
- Clearly identify the source repository and allow the user to uninstall an extension.

## Implementation order

1. Repository and package management (implemented).
2. Select and verify a JavaScript engine that supports hard execution and memory limits.
3. Define and test the versioned QuickNovel JS provider contract.
4. Connect the bounded HTTP helper through a controlled host bridge and implement HTML parsing.
5. Adapt a small, permissively licensed test extension end-to-end.
6. Only then connect compatible extensions to provider discovery and reading screens.

Until steps 2–5 are complete, the UI should describe installed bundles as installed extensions, not claim they are active providers.
