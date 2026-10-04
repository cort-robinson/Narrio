# Add-ons

Settings → Add-ons manages book metadata, audiobook sources, and ebook sources. Narrio bundles the six distinct schema 1.0.0 add-ons from the supplied list: Audible, Open Library, AudiobookBay, The Pirate Bay, Knaben audiobooks, and Knaben ebooks. The older JSONKeeper links describe the same AudiobookBay and Knaben IDs and can also be imported; importing an existing ID updates its definition while retaining its enabled state.

Enable or disable providers individually, refresh a definition from its saved URL, remove it, or import another HTTPS manifest URL. Settings and definitions persist on this device. Bundled definitions work without first downloading a manifest; refreshes are explicit. Removing a bundled provider persists across restarts. Reimport its URL to restore it. Provider status reflects the last actual lookup, not a guaranteed health check.

## Discovery and delivery

Catalog add-ons identify books without checking TorBox or creating playable recordings. Narrio still collapses metadata by title and author, uses Google Books as a built-in supplementary fallback, and hydrates selected Open Library works when descriptions are needed. Search and enrichment use enabled catalog definitions. Saved descriptive fields remain available after disabling a provider.

Audio source add-ons run during book-detail source discovery when TorBox is connected. Mapped torrent hashes enter the existing matching, cache, and file-verification pipeline. Remote `debridCache` hints never establish availability. Narrio verifies availability through the listener's TorBox account. Uncached torrents still require explicit preparation; source discovery does not add them to the account. Narrator/language hints are provider claims, not playback verification.

Ebook source add-ons run in automatic follow-along lookup and the text-source chooser. Matching requires the current recording's title and author, and only EPUB/TXT files checked as cached through TorBox are offered. No torrent is prepared automatically. Public Gutenberg lookup, companion files, and local imports remain available.

Indexed recordings retain the existing `knaben:<hash>` IDs and torrent delivery discriminator to preserve saved playback/source compatibility. `sourceAddonName` records visible provenance for newly discovered releases. Search results deduplicate by hash. Changing providers clears in-memory source results and keys metadata caches by the installed definitions/settings.

## Manifest contract

The manager supports declarative schema `1.0.0`, `source` and `catalog.search` adapters, and audiobook/ebook content types. It executes bounded GET or JSON POST requests, substitutes `{TITLE}`, `{AUTHOR}`, and `{QUERY}` (URL encoded in URLs; JSON escaped in bodies), and maps JSON response paths. Nested keys, numeric object keys, array indexes (`narrators[0].name`), and projected arrays (`authors[].name`) are supported. Catalog discovery-section definitions are accepted, but Narrio's existing browsing categories continue to use catalog search rather than separate add-on section pages.

Definitions require HTTPS public-host URLs without embedded credentials, IP literals, nonstandard ports, or redirects. Supported headers are Accept and Content-Type. There is no executable plugin code, credential interpolation, cache notification, or delivery-provider access. Responses are capped at 2 MB, manifests at 256 KB, results at 100 per provider, and request timeouts at 40 seconds. Declared request rates serialize requests per add-on; cancellation stops requests and rate-limit waits. One provider failure does not hide results from another; last errors appear in the manager. Refresh failures preserve the installed definition.

Only search terms and public request parameters reach an add-on endpoint. The manager never receives the TorBox key, playback URLs, account contents, or listening history. TorBox operations remain in the delivery module. Definitions/settings are excluded from Android backup with the rest of Narrio's local data.

## Verification

JVM fixtures cover all six bundled manifests, unsupported schemas/requests, JSON-path variants, request escaping, hash validation, root-array results, catalog-only identity, ebook/audio separation, provider failure isolation, cancellation, alias import, enable/remove/refresh persistence, and credential-free requests. A controlled Android settings test covers provider visibility, persisted disabling, and back navigation; it is included in CI smoke tests.

Live manifest retrieval and public provider searches are separate evidence from fixture tests. Provider availability may change; successful search responses do not establish authenticated TorBox playback or ebook retrieval.
