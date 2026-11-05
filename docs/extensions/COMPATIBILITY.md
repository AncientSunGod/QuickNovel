# QuickNovel LNReader extension compatibility

## Current status

QuickNovel has a repository manager for LNReader-style `plugins.min.json` manifests. It can add and refresh repositories, browse and search the catalogue, install/update/uninstall bundles, register installed extensions as providers, and expose supported provider settings.

The feature is **still a partial compatibility layer, not a drop-in LNReader runtime and not release-ready**. Some extension features are not implemented, and JavaScript executes in the app process rather than an isolated sandbox.

### Add the official LNReader v3 repository

Paste this manifest URL into the extension repository field:

```text
https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json
```

After refreshing the catalogue, choose an extension and install it. Installed extensions appear separately in the installed list, where they can be configured (when they declare settings) or uninstalled. Newly installed providers are added to the active provider list and their language is enabled once.

The language adapter recognizes the published v3 manifest's localized labels: Arabic, Bengali, Chinese, English, French, Indonesian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Thai, Turkish, Ukrainian, Vietnamese, and Multi (mapped to English so it remains visible with the default English provider filter).

## Provider flow

The QuickNovel adapter connects supported LNReader operations to the app's native provider interface:

1. Search calls `searchNovels(query, page)` for up to five pages (maximum 250 mapped results), stopping when a page is empty or repeats the previous page, and maps title, path, cover, and latest-chapter data.
2. The provider's home page calls `popularNovels` and maps available filter definitions into QuickNovel's sort/category/tag controls.
3. Novel details call `parseNovel`; chapter lists can continue through `parsePage`.
4. Reading calls `parseChapter`. Optional `customJS` and `customCSS` assets are downloaded at install/update time; custom JavaScript runs against a Cheerio-style parsed chapter, and custom CSS is inserted into the chapter HTML.
5. Opaque extension paths are preserved in internal URL fragments so a saved novel or chapter can route back to the extension. The internal fragment is not sent to the website.

Upstream Chikari and FirstKissNovel bundles are included as MIT-licensed fixtures. Tests use mocked HTTP responses to exercise both real upstream bundles; they do not substitute for testing a live site in the installed app.

## Supported runtime subset

- **Module helpers:** `@libs/defaultCover`, `@libs/novelStatus`, `@libs/filterInputs`, `@libs/storage`, `@libs/isAbsoluteUrl`, and `urlencode`.
- **Image requests:** the extension's declared `imageRequestInit.headers` are mapped onto QuickNovel cover requests (within a limited header allowlist).
- **HTTP:** `fetchApi` supports GET and POST with string request bodies. `fetchText` supports the same request options plus a requested text encoding. `fetchFile` supports bounded GET downloads and returns base64 data (up to 1 MB). Response status, status text, and headers are exposed.
- **Storage:** `@libs/storage` key/value data and `localStorage` persist per extension. `sessionStorage` is held in memory for the current app process and is cleared on uninstall/process exit.
- **Plugin settings:** Text, Switch, Select, and CheckboxGroup settings can be read, edited, validated, and saved in the extension settings UI.
- **Plugin filters:** Picker filters map to QuickNovel's existing home-page controls. One Checkbox/XCheckbox genre filter can be mapped to the tag selector; complex multi-select and exclusion editing is not fully represented.
- **HTML parsing:** `cheerio.load` supports common CSS selectors and operations including text/HTML/attribute access, nested `find`, iteration, basic traversal, contents, sibling access, node metadata, and removal.
- **htmlparser2:** a bounded synchronous `Parser` callback subset is provided for `onopentag`, `ontext`, and `onclosetag`.
- **Dates and promises:** a limited Day.js-compatible helper and synchronous Promise patterns (including `Promise.all`) are available.

Networking has request-count, timeout, response-size, redirect, and approved-host restrictions. The approved hosts include the extension site and public hostnames explicitly written as absolute HTTP(S) URLs in the downloaded bundle; resolved private/local addresses are rejected. Request headers are allowlisted. These controls reduce exposure; they do not turn the runtime into a security sandbox.

## Known compatibility gaps

The following are not implemented as full LNReader equivalents:

- `fetchProto`, protobuf encoding/decoding, AES helpers, and general `@libs/utils` modules.
- Binary, multipart, and arbitrary request bodies; unrestricted Fetch API behavior; browser APIs such as a full `window`/DOM environment.
- Full Cheerio/htmlparser2 behavior, all Day.js plugins, and true asynchronous event-loop/microtask scheduling.
- Search requests up to five `searchNovels` pages (maximum 250 mapped results), stopping on an empty or repeated page. Extensions that use a different pagination contract may still need an adapter.
- Full filter UI for multiple simultaneous checkbox selections, included/excluded genre groups, or arbitrary custom filter types.
- Every LNReader plugin setting type and extension-specific UI surface.

An extension may install successfully and still fail at runtime if it depends on an unsupported API. Unknown imports fail explicitly instead of being replaced with fake implementations.

## Security and release requirements

The Rhino runtime runs in the QuickNovel app process. It disables Java class access, enforces an instruction budget, caps inputs/results, and exposes a narrow host bridge, but it does not provide process isolation or a hard memory limit. Treat extension bundles as untrusted code; do not describe arbitrary third-party extensions as sandboxed or inherently safe.

Before this work is considered release-ready:

1. The latest commit must pass Android unit tests and debug APK assembly.
2. A real extension must be installed and exercised in the app through repository refresh, install/update, search, browse/filter, novel details, chapter reading, settings, and uninstall.
3. More than one current extension should be tested, especially plugins using different helpers and site request patterns.
4. Unsupported APIs and the in-process execution risk must remain visible to users.

## References

- [LNReader plugin documentation](https://github.com/LNReader/lnreader-plugins/tree/master/docs)
- [LNReader v3 plugin manifest](https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json)
- [Android JavaScriptEngine documentation](https://developer.android.com/develop/ui/views/layout/webapps/jsengine)

## HTTP 403 recovery and session behavior

The extension HTTP bridge uses the Android WebView user agent and same-host browser request hints where available. It reads the app WebView cookie jar before each request and stores response `Set-Cookie` values back into that jar. On the first 403 for an imported extension, it may open the same URL in the app's WebView resolver and retry the request once using the browser session.

A retry is not a guarantee of access. If the site still returns 403, requires an account, or denies the app/browser session, the extension must surface the denial. Do not treat a 403 response as an empty successful result.

For diagnosis, record the extension ID, requested URL host/path (never log cookie or authorization values), HTTP status, response content type, and whether the WebView recovery path ran. Verify the behavior against more than one extension and distinguish mocked tests from real-device/live-site tests.

## Saved text replacement migration

Older preference data may contain JSON objects materialized as `LinkedHashMap` rather than typed `TextReplacementRule` instances. The reader normalizes legacy `find`/`replace` maps before applying rules and writes the normalized list back to preferences. Keep this migration backward-compatible; do not clear the user's saved rules to work around deserialization errors.
