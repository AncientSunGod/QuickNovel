# QuickNovel extension compatibility

## Current status

QuickNovel can add LNReader repository manifests, browse entries, install/update/uninstall bundles, and register installed bundles as native providers. A partial JavaScript runtime now supports the Chikari bundle's fetch/helper pattern, common Cheerio selectors, fetchText, and basic URL helpers.

**Compatibility is still partial and the feature is not release-ready.** Imports and APIs outside the supported subset can fail. The runtime uses Rhino inside the app process; instruction limits do not provide process isolation or a hard memory limit. The app warns before installation and warns that some extensions may not work.

## Provider flow

The native adapter maps the supported LNReader operations to QuickNovel's provider contract:

1. Search calls searchNovels and maps novel title, path, and cover.
2. Novel details call parseNovel and map metadata plus chapters.
3. Chapter reading calls parseChapter and returns its HTML/text.
4. Opaque plugin paths are preserved in URL fragments so saved entries can route back to the extension without sending those fragments to the website.

An upstream Chikari bundle is included as an MIT-licensed fixture. Tests exercise its search, novel-details, and chapter parsing operations with mocked site responses. Additional tests cover provider routing, Promise.all, fetchText, and common Cheerio selectors. **These tests have not yet been confirmed by a successful Android build/test run.**

## Supported runtime subset

Current helper support includes:

- @libs/fetch: fetchApi and fetchText, GET only, with a request-header allowlist.
- @libs/defaultCover, @libs/novelStatus, and @libs/filterInputs.
- @libs/isAbsoluteUrl, urlencode, and per-extension persistent @libs/storage key/value data.
- cheerio.load with common CSS selection, text/HTML/attribute access, nested find, iteration, basic traversal, and removal.
- CommonJS-style exports and synchronous Promise patterns, including Promise.all.

Networking is bounded by request-count, response-size, timeout, redirect, and host restrictions. Requests and redirects are restricted to the extension's declared site host, and local/private network destinations are rejected. These controls reduce exposure but are not a security sandbox.

## Known compatibility gaps

The following are not currently implemented as complete LNReader-compatible modules:

- Plugin settings UI and WebView-backed localStorage/sessionStorage values. The @libs/storage key/value store is now persisted per extension.
- dayjs, htmlparser2, protobuf fetches, AES helpers, and general @libs/utils helpers.
- Full Cheerio behavior and all selector methods.
- Non-GET HTTP methods, full Fetch API behavior, arbitrary request scheduling, and true asynchronous Promise/microtask handling.
- Plugin icons, custom JavaScript/CSS execution, and interactive extension settings.

Plugins using these features may fail even if they install successfully. Unknown imports fail explicitly rather than being silently replaced with fake implementations.

## Security and release requirements

Downloaded JavaScript currently executes in-process. The runtime disables Java class access, limits instruction count, caps inputs/outputs, and exposes only narrow host functions, but it does not have process isolation or a hard memory limit. Do not describe it as safe for arbitrary third-party code.

Before treating this feature as release-ready:

1. Run the Android unit tests and assemble the debug APK successfully.
2. Exercise a real extension through search → novel details → chapter reading in the app.
3. Add compatibility fixtures for more than one repository plugin and cover common failures.
4. Choose a runtime with genuine process isolation compatible with the app's minimum Android version, or explicitly decide to raise that minimum. A previous probe found that AndroidX JavaScriptEngine 1.1.1 declares minSdk 26 while QuickNovel currently supports minSdk 23; adding it directly breaks manifest merging.
5. Keep networking restrictions, execution budgets, user-visible source attribution, confirmation before install/update, and an easy uninstall path.

Cash App Zipline is not a drop-in sandbox for arbitrary LNReader bundles: it is designed around Kotlin/JS modules and its documentation says it does not provide sandboxing or process isolation for untrusted code.

## References

- [LNReader plugin documentation](https://github.com/LNReader/lnreader-plugins/tree/master/docs)
- [Android JavaScriptEngine documentation](https://developer.android.com/develop/ui/views/layout/webapps/jsengine)
- [Cash App Zipline](https://github.com/cashapp/zipline)
