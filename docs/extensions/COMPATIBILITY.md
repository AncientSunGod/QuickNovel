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

## Provider flow

The QuickNovel adapter connects supported LNReader operations to the app's native provider interface:

1. Search calls `searchNovels` (currently page 1) and maps title, path, cover, and latest-chapter data.
2. The provider's home page calls `popularNovels` and maps available filter definitions into QuickNovel's sort/category/tag controls.
3. Novel details call `parseNovel`; chapter lists can continue through `parsePage`.
4. Reading calls `parseChapter`. Optional `customJS` and `customCSS` assets are downloaded at install/update time; custom JavaScript runs against a Cheerio-style parsed chapter, and custom CSS is inserted into the chapter HTML.
5. Opaque extension paths are preserved in internal URL fragments so a saved novel or chapter can route back to the extension. The internal fragment is not sent to the website.

An upstream Chikari bundle is included as an MIT-licensed fixture. Tests use mocked HTTP responses to exercise the real bundle and the provider adapter; they do not substitute for testing a live site in the installed app.

## Supported runtime subset

- **Module helpers:** `@libs/defaultCover`, `@libs/novelStatus`, `@libs/filterInputs`, and `@libs/storage`.
- **HTTP:** `fetchApi` supports GET and POST with string request bodies. `fetchText` supports the same request options plus a requested text encoding. `fetchFile` supports bounded GET downloads and returns base64 data (up to 1 MB). Response status, status text, and headers are exposed.
- **Storage:** `@libs/storage` key/value data and `localStorage` persist per extension. `sessionStorage` is held in memory for the current app process and is cleared on uninstall/process exit.
- **Plugin settings:** Text, Switch, Select, and CheckboxGroup settings can be read, edited, validated, and saved in the extension settings UI.
- **Plugin filters:** Picker filters map to QuickNovel's existing home-page controls. One Checkbox/XCheckbox genre filter can be mapped to the tag selector; complex multi-select and exclusion editing is not fully represented.
- **HTML parsing:** `cheerio.load` supports common CSS selectors and operations including text/HTML/attribute access, nested `find`, iteration, basic traversal, contents, sibling access, node metadata, and removal.
- **htmlparser2:** a bounded synchronous `Parser` callback subset is provided for `onopentag`, `ontext`, and `onclosetag`.
- **Dates and promises:** a limited Day.js-compatible helper and synchronous Promise patterns (including `Promise.all`) are available.

Networking has request-count, timeout, response-size, redirect, and approved-host restrictions. Request headers are allowlisted. These controls reduce exposure; they do not turn the runtime into a security sandbox.

## Known compatibility gaps

The following are not implemented as full LNReader equivalents:

- `fetchProto`, protobuf encoding/decoding, AES helpers, and general `@libs/utils` modules.
- Binary, multipart, and arbitrary request bodies; unrestricted Fetch API behavior; browser APIs such as a full `window`/DOM environment.
- Full Cheerio/htmlparser2 behavior, all Day.js plugins, and true asynchronous event-loop/microtask scheduling.
- Search pagination beyond the first `searchNovels` page.
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
