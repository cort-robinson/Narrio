# Add-ons

Settings → Add-ons manages book metadata, audiobook sources, and ebook sources. Narrio bundles Audible, Open Library, AudiobookBay, The Pirate Bay, Knaben audiobooks, Knaben ebooks, and Anna's Archive. The older JSONKeeper links describe the same AudiobookBay and Knaben IDs and can also be imported; importing an existing ID updates its definition while retaining its enabled state.

Enable or disable providers individually, refresh a definition from its saved URL, remove it, or import another HTTPS manifest URL. Settings and definitions persist on this device. Bundled definitions work without first downloading a manifest; refreshes are explicit. Removing a bundled provider persists across restarts. Reimport its URL to restore it. Provider status reflects the last actual lookup, not a guaranteed health check.

New bundled providers are installed once on upgrade. Existing disabled settings and previously removed providers are preserved; removing the new provider prevents its return on later restarts.

## Discovery and delivery

Catalog add-ons identify books without checking TorBox or creating playable recordings. Narrio still collapses metadata by title and author, uses Google Books as a built-in supplementary fallback, and hydrates selected Open Library works when descriptions are needed. Search and enrichment use enabled catalog definitions. Saved descriptive fields remain available after disabling a provider.

Audio source add-ons run during book-detail source discovery when TorBox is connected. Mapped torrent hashes enter the existing matching, cache, and file-verification pipeline. Remote `debridCache` hints never establish availability. Narrio verifies availability through the listener's TorBox account. Uncached torrents still require explicit preparation; source discovery does not add them to the account. Narrator/language hints are provider claims, not playback verification.

Ebook source add-ons run in automatic follow-along lookup and the text-source chooser. Matching requires the current recording's title and author, and only EPUB/TXT files checked as cached through TorBox are offered. No torrent is prepared automatically. Public Gutenberg lookup, companion files, and local imports remain available.

Anna's Archive uses the `ebook-search` capability. In a book's **Find ebook** sheet, choose **Search Anna's Archive** to open an EPUB search for its title and author inside Narrio. Select an edition, complete any website verification, and tap its final file download. Narrio intercepts the download and asks before adding it to this book. Opening a search never attaches an unverified edition.

With TorBox connected, Narrio first checks its web-download cache using the MD5 of the exact selected download URL. A cache miss starts a TorBox web download; an existing transfer is reused. Once the ebook is ready, Narrio retrieves and saves its EPUB/TXT file on the phone. Pending transfers remain in TorBox for retry rather than starting a duplicate website download. Ready ebook files already in your TorBox web-download library also participate in normal title/author matching. The Anna record's file-content MD5 is not used as a TorBox URL hash; see [TorBox's hash contract](https://support.torbox.app/en/articles/13681109-technical-getting-hashes-for-searches) and [web-download API](https://api-docs.torbox.app/).

If TorBox is disconnected or cannot handle the selected link, Narrio downloads through the verified website session and imports the file into the selected book. Browser cookies and referrers are not passed to TorBox, forwarded to another origin, or stored with an edition. Redirects must stay on public HTTPS URLs. Downloads are bounded to 20 MB and validated as EPUB or UTF-8 text before import. PDF/Kindle files, HTML verification pages, and malformed/DRM ebooks are rejected. Closing the website cancels Narrio's transfer; any transfer already created in TorBox stays in that account.

[Anna's Archive's official FAQ](https://annas-archive.gl/faq#api) documents a membership-only fast-download API and browser verification for free downloads. Narrio uses the website for search and user verification, without scraping search results or handling membership keys. Website availability and TorBox host support remain dependent on those providers.

Indexed recordings retain the existing `knaben:<hash>` IDs and torrent delivery discriminator to preserve saved playback/source compatibility. `sourceAddonName` records visible provenance for newly discovered releases. Search results deduplicate by hash. Changing providers clears in-memory source results and keys metadata caches by the installed definitions/settings.

## Manifest contract

The manager supports declarative schema `1.0.0`, `source`, `catalog.search`, and `ebook-search` adapters, and audiobook/ebook content types. It executes bounded GET or JSON POST requests, substitutes `{TITLE}`, `{AUTHOR}`, and `{QUERY}` (URL encoded in URLs; JSON escaped in bodies), and maps JSON response paths. Nested keys, numeric object keys, array indexes (`narrators[0].name`), and projected arrays (`authors[].name`) are supported. Catalog discovery-section definitions are accepted, but Narrio's existing browsing categories continue to use catalog search rather than separate add-on section pages.

An ebook website extension declares `contentType: "ebook"`, `provides: ["ebook-search"]`, and `adapters.ebook-search.request` with a public HTTPS `url` and `method: "GET"`. Its URL must include `{TITLE}` or `{QUERY}`; `{QUERY}` expands to the book title and known author. No request body, headers, or response mapping is used. Creating the link makes no network request; the in-app WebView opens only after the user taps it. See the [bundled Anna's Archive manifest](../app/src/main/assets/addons/annas-archive-ebooks.json). Refreshing that manifest updates the mirror/search URL through the existing manager.

Definitions require HTTPS public-host URLs without embedded credentials, IP literals, nonstandard ports, or redirects. Supported headers are Accept and Content-Type. There is no executable plugin code, credential interpolation, cache notification, or delivery-provider access. Responses are capped at 2 MB, manifests at 256 KB, results at 100 per provider, and request timeouts at 40 seconds. Declared request rates serialize requests per add-on; cancellation stops requests and rate-limit waits. One provider failure does not hide results from another; last errors appear in the manager. Refresh failures preserve the installed definition.

Only search terms and public request parameters reach an add-on endpoint. The manager never receives the TorBox key, playback URLs, account contents, or listening history. TorBox operations remain in the delivery module. Definitions/settings are excluded from Android backup with the rest of Narrio's local data.

## Verification

JVM fixtures cover all bundled manifests, unsupported schemas/requests, JSON-path variants, request escaping, hash validation, root-array results, catalog-only identity, ebook/audio separation, provider failure isolation, cancellation, alias import, enable/remove/refresh persistence, credential-free browser links, and upgrade installation without restoring removed providers. Controlled JVM fixtures also cover TorBox cache hits, misses, pending-transfer reuse, ready account files, cookie isolation, redirects, fallback, and cancellation. Android settings and ebook-sheet tests cover provider visibility, persisted disabling, book-specific in-app search, download interception and confirmation, and preserved listening position; they are included in CI smoke tests.

Live manifest retrieval and public provider searches are separate evidence from fixture tests. Provider availability may change; successful search responses do not establish authenticated TorBox playback or ebook retrieval.
