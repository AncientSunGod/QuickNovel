# QuickNovel extension compatibility

## Current status

QuickNovel can add a repository manifest, display its entries, download a bundle to private app storage, update it, and uninstall it. This is the **catalogue and package-management layer** only. A downloaded LNReader plugin is not yet a working QuickNovel provider.

## Why a compatibility adapter is required

QuickNovel's native provider contract is the Kotlin `MainAPI` interface:
- `search(query)` returns `List<SearchResponse>?`
- `load(url)` returns a `LoadResponse?` (usually a `StreamResponse` with chapter entries)
- `loadHtml(url)` returns chapter content
- optional main-page methods return `HeadMainPageResponse`

LNReader plugins commonly use a different JavaScript API and bundled module imports. A JavaScript engine by itself does not supply React Native, Metro module resolution, LNReader helper libraries, Cheerio, or QuickNovel's HTTP client. QuickNovel currently does not execute downloaded extension JavaScript. Therefore a bundle passing a JavaScript smoke test does not establish compatibility.

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
- Treat a JavaScript engine's timeout and memory controls as necessary but not sufficient: the host must also restrict every exposed capability. A runtime that has no sandbox or process isolation must not be described as safe for arbitrary third-party code.

## Implementation order

1. Repository and package management (implemented).
2. Select and verify a JavaScript engine that supports hard execution and memory limits, then document its isolation limits.
3. Define and test the versioned QuickNovel JS provider contract.
4. Connect the bounded HTTP helper through a controlled host bridge and implement HTML parsing.
5. Adapt a small, permissively licensed test extension end-to-end.
6. Only then connect compatible extensions to provider discovery and reading screens.

Until steps 2–5 are complete, the UI should describe installed bundles as installed extensions, not claim they are active providers.

## Runtime investigation (October 2026)

The current candidate to prototype is AndroidX JavaScriptEngine's `JavaScriptSandbox`, rather than enabling the old in-process QuickJS binding. Android's documentation says JavaScript runs in a separate sandboxed process and requires a runtime support check; support depends on API level (26+) and the installed WebView implementation. Heap limits and isolate-termination features are optional and must be checked at runtime. A future adapter must not assume these features exist on every device.

Reference: [Android Developers — Executing JavaScript and WebAssembly](https://developer.android.com/develop/ui/views/layout/webapps/jsengine).

This is a candidate, not an implementation or a security guarantee. A first dependency probe confirmed an important compatibility blocker: this app currently supports minSdk 23, while `androidx.javascriptengine:javascriptengine:1.1.1` declares minSdk 26. Adding it directly breaks Android manifest merging. The dependency and probe were therefore reverted rather than silently dropping Android 6–7 support or forcing a manifest override. Before adopting it, decide whether to keep minSdk 23 with a separately isolated runtime/module strategy or deliberately raise the minimum supported Android version. Then test actual timeout/cancellation and memory-limit behavior on supported devices and provide a clear unavailable-runtime message where required features are missing. A host-side future timeout alone is not proof that computation stopped; the isolate must be terminated or discarded using a supported mechanism.

[Cash App Zipline](https://github.com/cashapp/zipline) is not a drop-in runtime for arbitrary LNReader bundles: it is designed around Kotlin/JS modules and service interfaces, and its own documentation explicitly says it does not provide a sandbox or process isolation for untrusted code. Its QuickJS memory/interrupt controls are useful engineering references, but do not make it a safe substitute for process isolation.

Even with a process-isolated engine, extension code must receive only a narrow, explicit bridge. No ambient Android access, filesystem access, arbitrary native calls, or unrestricted networking should be exposed. The host must enforce request counts, response-size limits, allowed headers, redirect policy, per-operation deadlines, and output-size limits independently of what the plugin requests.

## Bounded JavaScript execution prototype (October 2026)

An in-process Rhino execution core now exists in `ExtensionJsRuntime`. It can execute a small CommonJS-style export and enforces an instruction-count budget; tests cover a successful simple export, a rejected unknown import, and an instruction-heavy function.

**This does not make LNReader repository bundles runnable yet.** Real bundles may depend on Metro's module registry, asynchronous APIs, LNReader's helper modules, and Cheerio/htmlparser2 behavior. Those imports deliberately fail closed in this prototype. The engine also runs in the app process, so instruction limits alone do not prevent memory-exhaustion attacks; it must not be wired to downloaded third-party bundles as a production runtime.

Next required work remains: implement and test the actual LNReader bundle/module format, a compatible asynchronous fetch bridge, HTML parsing compatibility, and an isolation strategy that can stop a runaway operation without endangering the app. Then connect the tested adapter to `MainAPI` and validate search, novel details, and chapter reading against a real repository extension. Until that end-to-end test passes, extensions remain catalogued/installed but inactive.

### HTML parser prototype

`ExtensionHtmlParser` now provides bounded CSS-selector extraction, text extraction, and attribute lookup using the app's existing Jsoup dependency. Its input, selector, result-count, and text sizes are capped, with unit tests for representative HTML. This is a host-side building block, **not yet a Cheerio-compatible JavaScript module**: LNReader bundles cannot call it through `require('cheerio')` or `htmlparser2` yet.

### First real-bundle compatibility slice

The runtime now has a narrow module bridge for the imports used by the upstream **Chikari** English plugin: `@libs/fetch`, `@libs/defaultCover`, `@libs/novelStatus`, and `@libs/filterInputs`. Its fetch bridge allows GET requests only, caps each operation at 20 requests, restricts requests and redirects to the extension's declared site host, rejects localhost/private network resolutions, and uses the existing response/time limits. A synchronous Promise compatibility shim supports the transpiled async pattern used by that bundle.

A native `ExtensionMainAPI` now adapts `searchNovels`, `parseNovel`, `parseChapter`, and optional `resolveUrl` into QuickNovel's provider interface. Installed extensions are included in provider discovery, and newly installed providers are enabled by default. Opaque LNReader paths are preserved in URL fragments so QuickNovel can route chapter loads back to the plugin without sending those fragments to the website.

An upstream Chikari bundle is included as an MIT-licensed test fixture, and a test exercises its actual `searchNovels` function with a deterministic mocked API response. This validates one real bundle's module-loading and async pattern; it does **not** establish broad compatibility with all repository plugins. Plugins importing other helpers, Cheerio/htmlparser2, settings/storage APIs, or requiring true asynchronous scheduling remain unsupported. Rhino still runs in-process, so the instruction budget is not equivalent to process isolation or a hard memory limit.

Build/CI verification for these newest commits is still pending; the repository status endpoint returned no checks in this session. Do not treat the feature as release-ready until the Android build and unit tests pass, and a real plugin's search → details → chapter flow is exercised through the UI.
