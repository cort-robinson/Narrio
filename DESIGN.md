---
name: Narrio
description: An authored native Android listening room
colors:
  night-copper: "#E8AF79"
  night-on-copper: "#342312"
  night-copper-container: "#59402C"
  night-on-copper-container: "#FFDDBB"
  day-copper: "#805031"
  day-on-copper: "#FFFFFF"
  day-copper-container: "#FFDBC0"
  day-on-copper-container: "#3A2110"
  night-sage: "#B1C9AC"
  night-on-sage: "#1F3328"
  night-selected: "#304739"
  night-on-selected: "#D3E7CA"
  day-sage: "#476447"
  day-on-sage: "#FFFFFF"
  day-selected: "#D4E5C9"
  day-on-selected: "#223B24"
  night-ground: "#101B1A"
  night-text: "#F4EDDE"
  night-surface-low: "#15221F"
  night-surface: "#1B2A27"
  night-surface-high: "#263832"
  night-muted-text: "#B9C8BD"
  night-outline: "#809087"
  night-outline-muted: "#36483F"
  day-ground: "#F6F0E5"
  day-text: "#20332C"
  day-surface-low: "#F3EBDC"
  day-surface: "#EEE5D5"
  day-surface-high: "#E7DCCA"
  day-muted-text: "#536359"
  day-outline: "#727C71"
  day-outline-muted: "#D0D1C3"
  night-error: "#FFB4AB"
  night-on-error: "#690005"
typography:
  display-small:
    fontFamily: "Newsreader"
    fontSize: "36px"
    fontWeight: 400
    lineHeight: "40px"
    letterSpacing: "-0.4px"
  headline-large:
    fontFamily: "Newsreader"
    fontSize: "32px"
    fontWeight: 400
    lineHeight: "36px"
    letterSpacing: "-0.4px"
  headline-medium:
    fontFamily: "Newsreader"
    fontSize: "28px"
    fontWeight: 400
    lineHeight: "32px"
    letterSpacing: "-0.4px"
  headline-small:
    fontFamily: "Newsreader"
    fontSize: "24px"
    fontWeight: 400
    lineHeight: "28px"
    letterSpacing: "-0.4px"
  title-large:
    fontFamily: "Newsreader"
    fontSize: "22px"
    fontWeight: 500
    lineHeight: "28px"
    letterSpacing: "-0.4px"
  title-medium:
    fontFamily: "Manrope"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "23px"
    letterSpacing: "0px"
  title-small:
    fontFamily: "Manrope"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
    letterSpacing: "0px"
  body-large:
    fontFamily: "Manrope"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "26px"
    letterSpacing: "0px"
  body-medium:
    fontFamily: "Manrope"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "22px"
    letterSpacing: "0px"
  body-small:
    fontFamily: "Manrope"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "19px"
    letterSpacing: "0px"
  label-large:
    fontFamily: "Manrope"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
    letterSpacing: "0px"
  label-medium:
    fontFamily: "Manrope"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: "18px"
    letterSpacing: "0px"
  label-small:
    fontFamily: "Manrope"
    fontSize: "11px"
    fontWeight: 500
    lineHeight: "16px"
    letterSpacing: "0px"
rounded:
  cover: "8px"
  selection: "12px"
  container: "14px"
spacing:
  micro: "4px"
  inline: "8px"
  compact: "12px"
  row: "16px"
  inset: "20px"
  page: "24px"
  section: "28px"
  wide: "32px"
components:
  button-primary-night:
    backgroundColor: "{colors.night-copper}"
    textColor: "{colors.night-on-copper}"
    typography: "{typography.label-large}"
  button-tonal-night:
    backgroundColor: "{colors.night-selected}"
    textColor: "{colors.night-on-selected}"
    typography: "{typography.label-large}"
  button-outlined-night:
    textColor: "{colors.night-copper}"
    typography: "{typography.label-large}"
  search-field-night:
    textColor: "{colors.night-text}"
    rounded: "{rounded.container}"
  recovery-container-night:
    backgroundColor: "{colors.night-surface}"
    rounded: "{rounded.container}"
    padding: "{spacing.inset}"
  selected-row-night:
    backgroundColor: "{colors.night-selected}"
    rounded: "{rounded.selection}"
  mini-player-night:
    backgroundColor: "{colors.night-surface}"
    typography: "{typography.title-small}"
  button-primary-day:
    backgroundColor: "{colors.day-copper}"
    textColor: "{colors.day-on-copper}"
    typography: "{typography.label-large}"
  button-tonal-day:
    backgroundColor: "{colors.day-selected}"
    textColor: "{colors.day-on-selected}"
    typography: "{typography.label-large}"
  button-outlined-day:
    textColor: "{colors.day-copper}"
    typography: "{typography.label-large}"
  search-field-day:
    textColor: "{colors.day-text}"
    rounded: "{rounded.container}"
  recovery-container-day:
    backgroundColor: "{colors.day-surface}"
    rounded: "{rounded.container}"
    padding: "{spacing.inset}"
  selected-row-day:
    backgroundColor: "{colors.day-selected}"
    rounded: "{rounded.selection}"
  mini-player-day:
    backgroundColor: "{colors.day-surface}"
    typography: "{typography.title-small}"
---

# Design System: Narrio

## Overview

**Creative North Star: "Listening room"**

Narrio is a quiet, personal listening room. In the default palette, warm ink and warm paper grounds give the artwork space, copper makes listening actions easy to find, and sage supports narration and selection. The original type pairing gives story titles a serif voice and tasks a clear sans serif.

The implemented world uses Material 3 navigation and controls inside an authored type and color theme. Lists remain open on the page; containers identify artwork, recovery, preparation, or selection. Compact windows keep one task in view, while wider windows put discovery and the selected book, recording, or listening session beside each other.

Listening room remains the default. Local Appearance settings offer paired palettes, one custom palette, and font and text-size choices within the same layouts and semantic roles.

This guide records the current Compose implementation. The original surface concept and first-viewport strategy remain as historical direction evidence in [.impeccable/direction-contract.md](.impeccable/direction-contract.md); the current catalog and details flow is described under Components below. FORM provenance is direction seed `73f8a99b`, grounded index 4, catalog revision `c3b204a1eed6`; the contract records the Operate surface and the code-led path. The exact seed output is [.impeccable/concept-seed-evidence.txt](.impeccable/concept-seed-evidence.txt). There is no approved decision comp or available QUALITY BAR card.

**Key Characteristics:**

- Default warm ink at night; warm paper by day.
- Default Newsreader story titles and Manrope metadata and controls.
- Paired local palettes and independent font and text-size choices.
- Artwork is the centerpiece; functional content stays legible around it.
- Material navigation adapts to available window width and reported hinge posture.
- Explicit loading, preparation, empty, selected, and recovery states.
- Cache-aware source selection with separate cloud preparation and phone storage state.

The color, family, and weight tokens above record Listening room with the Narrio font pairing and Default text size from [Theme.kt](app/src/main/java/app/narrio/ui/Theme.kt). The [DESIGN.md format](https://raw.githubusercontent.com/google-labs-code/design.md/main/docs/spec.md) supports CSS dimensions, so frontmatter lengths are a baseline display translation: the same numeric scalar expressed in px. Android implementation uses dp for geometry and sp for typography. Exact native values, role mappings, selectable settings, and sample limitations live in [.impeccable/design.json](.impeccable/design.json), under `extensions.android`; never paste the px adapter into Compose.

## Colors

The Listening room default pairs copper and sage against warm neutral grounds. Its Night and Day schemes retain their own contrast pairings rather than deriving one by inversion.

### Primary

- **Copper:** `night-copper` / `day-copper` identify listening actions, transport, progress, and action labels; their `on-copper` partners carry content inside a filled primary control.
- **Copper containers:** the paired container roles are defined by the theme for Material components. They are not a second independent accent.

### Secondary

- **Sage:** the `sage` pair identifies narrator and secondary context.
- **Selected sage:** `selected` / `on-selected` support Material tonal controls and navigation, with explicit selected-row backgrounds in the source picker and audio-parts sheet.

### Tertiary

Material tertiary and its container/content partners reuse the secondary roles in both the original schemes and generated palettes. They do not introduce another independent accent.

### Neutral

- **Room ground:** `ground` maps to both background and surface; `text` maps to both onBackground and onSurface.
- **Tonal surfaces:** `surface-low`, `surface`, and `surface-high` separate the rail-adjacent listening pane, navigation, mini-player, sheets, and recovery or preparation containers.
- **Complete surface roles:** the original schemes map lowest/highest containers, dim/bright surfaces, and tint to existing ground, tonal-surface, and primary tokens. Inverse surface/content roles reuse the light/dark text tokens; inverse primary uses the opposite mode's copper. Exact mappings are in the Android sidecar.
- **Muted ink:** `muted-text` carries explanatory text and metadata.
- **Outlines:** `outline` serves Material outlined controls; `outline-muted` serves dividers and the mini-player progress track.
- **Error:** the explicit Night error pair remains part of the theme. Day inherits the Material light scheme's default error roles; no custom Day error palette was extracted.

The original garden cover is an artwork-specific exception: its cream text and dark green scrim remain fixed over the image while provider art loads or when no cover is available. Per-book cover palettes belong to artwork, not global interface tokens.

**The Paired Scheme Rule.** Resolve interface colors through the selected palette's MaterialTheme.colorScheme. Night, Day, and System are independent of palette choice. Listening room retains its authored schemes; optional presets and custom palettes derive contrast-aware roles from separate Night and Day seeds. The app does not use wallpaper-derived dynamic color.

### Palette choices and custom colors

Settings → Appearance offers five paired presets: Listening room (copper and sage), Ocean (sea glass and blue ink), Forest (fern and golden light), Rosewood (rose and plum), and Graphite (quiet neutral tones). Their exact seeds and generation behavior are recorded under `extensions.android.appearance`; the default frontmatter remains the Listening room baseline.

One named custom slot stores separate Night and Day accent, supporting, and background seeds in opaque sRGB. The generator derives readable content, actions, containers, outlines, error, and inverse roles; accent and supporting seeds can move toward readable ink where contrast requires it. The custom preview shows those derived roles rather than promising that a seed becomes an unchanged text color.

## Typography

**Default Display Font:** bundled Newsreader variable font.

**Default Body and Control Font:** bundled Manrope variable font.

Newsreader is expressive at normal weight; Manrope gives metadata and actions an even, clear rhythm. Theme.kt supplies a complete Material role table. The table and frontmatter record the original Narrio pairing at Default size. The frontmatter captures roles used by current screens or their Material controls; displayLarge and displayMedium are declared in the native theme but have no current screen application.

| Native role | Family | Size / line height (sp) | Weight | Typical use |
| --- | --- | --- | --- | --- |
| displaySmall | Newsreader | 36 / 40 | 400 | Repeated page headings in Discover, My shelf, and Settings. |
| headlineLarge | Newsreader | 32 / 36 | 400 | Book and recording titles, large cover lettering, and tabletop book context. |
| headlineMedium | Newsreader | 28 / 32 | 400 | Wordmark, player title, sheet/dialog headings, and empty-state titles. |
| headlineSmall | Newsreader | 24 / 28 | 400 | Shelf, description, source, and settings section headings. |
| titleLarge | Newsreader | 22 / 28 | 500 | Regular cover lettering and the quiet player closing line. |
| titleMedium | Manrope | 16 / 23 | 600 | Book and shelf rows, context bars, preparation state, and bookmark times. |
| titleSmall | Manrope | 14 / 20 | 600 | Matching recording releases, source options, parts, and account status. |
| bodyLarge | Manrope | 16 / 26 | 400 | Book and recording author and the Material text-field body role. |
| bodyMedium | Manrope | 14 / 22 | 400 | Descriptions, instructions, and supporting context. |
| bodySmall | Manrope | 12 / 19 | 400 | Author, narrator, source, and playback metadata. |
| labelLarge | Manrope | 14 / 20 | 600 | Material action labels. |
| labelMedium | Manrope | 12 / 18 | 600 | Elapsed/remaining times, metadata, preparation labels, and chips. |
| labelSmall | Manrope | 11 / 16 | 500 | Cover authors, saved progress, and duration captions. |

At Default size, Newsreader roles use slightly tight tracking (-0.4 sp); Manrope and Android roles use neutral tracking (0 sp). The bundled variable font weight axis is set per declared weight. There is no uppercase eyebrow or monospace label system.

Appearance provides Narrio (the original pairing), Manrope throughout, Newsreader throughout, and Android's default family. These choices use the existing bundled fonts or the device family, with no font downloads. Default, Comfort, and Large multiply font size and line height by 1.0, 1.1, and 1.2; Newsreader tracking scales with them. Native sp values retain the device's system font scaling on top.

**The Two Voices Rule.** In the default Narrio pairing, Newsreader owns display, headline, and titleLarge; Manrope owns remaining titles, body, labels, and controls. Selected fonts still use those semantic Material roles and their hierarchy. Keep system font scaling in sp.

## Layout

The usual page inset is generous (24 dp). Discover uses a slightly tighter top inset (20 dp) and a lower inset (28 dp). Related metadata uses shorter gaps (4, 8, or 12 dp); book and shelf rows separate art and text (16 dp). Section and page groups commonly use 24 or 28 dp. Component-specific gaps also occur; the build is not a strict universal grid.

[NarrioApp.kt](app/src/main/java/app/narrio/ui/NarrioApp.kt) chooses expanded composition at a current window width of at least 600 dp, except during open tabletop playback. Compact catalog destinations use a three-destination Material navigation bar; an active mini-player sits above it. Detail and full player replace the catalog view on compact windows.

Expanded composition uses an 80 dp navigation rail. Without a separating vertical hinge, the catalog begins at 53% of the width remaining after the rail; its width is bounded by the source's 250 dp pane minima. The secondary pane displays the selected book or recording, current player, or welcome state. With a separating vertical hinge, pane positioning uses the reported bounds and leaves a gap at least 1 dp wide. Both content panes scroll independently.

Horizontal HALF_OPENED posture becomes tabletop only while the player is open. The upper pane is bounded by the hinge after subtracting the status inset. Its cover height is the smaller of available height or `upper width × 0.55 × 1.45`; cover width is `height ÷ 1.45`. The context column scrolls within that upper region. The hinge gap is the greater of the reported gap and 12 dp; the lower transport pane scrolls with 24 dp padding. These are current layout calculations, not physical device measurements.

Safe drawing insets protect the top and horizontal edges; full/detail/expanded content also applies navigation-bar padding. Settings applies IME padding. System Back collapses the player, returns a recording chosen from source results to its catalog details, closes details, or returns another destination to Discover.

Browse category chips scroll horizontally at compact and expanded widths and disappear while a search query is entered. SourcePicker keeps its content in a vertically scrollable native sheet, with weighted option text that can wrap around its radio controls. Long release names and larger system text expand the sheet content instead of clipping the source and preparation actions. App and sheet system-bar icons use foreground contrast against the actual scheme background, including custom palettes. These behaviors are implemented in CatalogScreens.kt, NarrioApp.kt, and DetailAndSettings.kt; they do not introduce new breakpoints or fixed modal geometry.

**The Window First Rule.** Choose the composition from current window width and WindowManager features. Do not select a layout from the device name or assume physical Fold dimensions.

**The Hinge Is Space Rule.** Keep the reported separating region free of content. In tabletop playback, constrain artwork and book context to the upper pane and let lower controls scroll independently.

The 15 reviewed native captures under [.impeccable/review](.impeccable/review/) include compact and expanded windows, Night and Day, enlarged type, a separating hinge, and tabletop. [finish-verdict.md](.impeccable/review/finish-verdict.md) reports the tabletop correction resolved with ship disposition at that correction's scope. Hinge postures were injected through WindowManager testing; physical Fold 8 geometry, gestures, refresh rate, and performance are not established by these images.

The [1.1 finish review](.impeccable/review/v1.1/finish-review.md) returns ship with no material fixes for the introduced source, cache, preparation, and phone-download regions. Its 12 cached-source, uncached-source, preparation, and offline-settings captures cover phone, phone with enlarged type, and expanded layouts. Availability and cloud progress in those images are synthetic SourceExperienceTest fixtures. Preparation and settings captures intentionally show scrolled regions. The additional [offline release shelf](.impeccable/review/v1.1/offline-shelf-release-phone.png) shows a real downloaded M4B in the final signed release. Offline MP3/M4B playback, seeking, later-part behavior, pause/resume, and cold restart passed native validation. The final signed release additionally passed an offline M4B cold restart; see [offline-native-final.txt](verification/offline-native-final.txt), [release-offline.json](verification/release-offline.json), and [offline-restart.json](verification/offline-restart.json). The user subsequently confirmed the requested live cached-M4B and basic fold/unfold phone flow; see [user-device-1.1.json](verification/user-device-1.1.json). Subsequent direct signed-release checks on the connected Android 17 phone verified live multipart TorBox listening, later-part seeking, background/screen-off continuation, system headset-button events, network recovery, and saved-position reopening; see [physical-android-1.1.json](verification/physical-android-1.1.json). These functional checks do not extend the historical screenshot review into a measured Samsung hinge, Bluetooth-routing, or performance claim. The original review report remains a historical snapshot; exact Samsung hardware geometry and performance are outside these functional checks.

The separate [Appearance finish review](.impeccable/review/appearance/finish-review.md) returns ship with no material fixes for this branch's customization feature. Its 18 named native region captures cover phone, expanded, and enlarged-text windows, including system scaling 1.3 with app Large 1.2. The previews use synthetic story content and a custom Aurora palette. This review and [appearance.json](verification/appearance.json) establish the scoped emulator appearance behavior; they add no signed-release, physical-phone, or audio certification to the historical evidence above.

The metadata-first catalog and Find sources increment is documented from current Compose source. Rendered Android QA remains unverified: the T3 Android device is unavailable, no adb devices are connected, and no screenshots exist for this increment. The historical reviews above remain valid only for their recorded scope; they do not establish the new catalog, matching-recording rows, or return navigation at phone, expanded, Night/Day, or enlarged-text sizes.

## Elevation & Depth

Depth is primarily tonal. Catalog rows sit directly on the ground; dividers separate longer sections. The secondary pane uses surfaceContainerLow, the mini-player and functional status containers use surfaceContainer, and selected rows use secondaryContainer. The mini-player explicitly sets tonal elevation to zero (0 dp). Material sheets and dialogs retain their library elevation behavior; no custom shadow token or elevation scale has been authored.

Atmospheric depth comes from the original garden cover image and its contrast scrim. These are static artwork treatments, not looping effects or a substitute for real media. No custom decorative animation duration or easing token exists.

**The Quiet Surface Rule.** Use tonal surfaces and outlineVariant dividers to distinguish functional regions. Preserve Material sheet and dialog elevation; the implementation has no custom shadow vocabulary.

**The Artwork Contrast Rule.** Keep original artwork and its text scrim together. The gradient over the garden cover is a legibility treatment already present in the build.

Playback publication follows visibility: the service loop runs at one second while visible and five seconds while hidden, publishing in the hidden state while playback or a sleep timer is active. Background audio updates do not literally stop. These intervals appear in [ListeningService.kt](app/src/main/java/app/narrio/playback/ListeningService.kt), with lifecycle visibility set in NarrioApp.kt.

## Shapes

Covers have quietly rounded corners (8 dp). Selection rows and the credential field use a compact curve (12 dp). Search, recovery, and preparation containers use a slightly softer curve (14 dp).

The prominent play control is a true CircleShape (82 dp). Other buttons, chips, navigation indicators, sheets, and dialogs use Material defaults because the app does not override MaterialTheme.shapes. Preserve those native silhouettes rather than assigning all controls the container radius.

## Components

### Buttons and transport

Filled primary buttons carry Find sources, Listen, Discover, and Connect actions. Filled tonal buttons support quieter actions. Outlined controls hold Save, speed, sleep, and parts; text buttons carry retry, read-more, and subordinate actions. Their default colors and interaction states come from Material 3. Source-level enabled conditions express loading, account, and saved state.

Player transport gives the circular play/pause control a larger target (82 dp) and skip controls medium targets (56 dp), with real buffering progress inside the primary control. The slider is disabled until duration is known, exposes the listening-position description, and seeks when dragging finishes. See [PlayerScreen.kt](app/src/main/java/app/narrio/ui/PlayerScreen.kt).

### Inputs and chips

Search is a single-line OutlinedTextField with a search icon, clear action, and the container corner. The credential field is a password-transformed OutlinedTextField with the selection corner. Native field focus, outlines, keyboard interaction, and disabled state remain Material-owned.

FilterChip drives browse category and Appearance mode, text-size, and custom color-role selection. Chip groups use short gaps (8 dp); categories scroll horizontally and Appearance groups wrap. Category chips appear only with a blank query, and entering a query resets the browse category to All. Discovery has no source-scope chip row. Selected states remain native Material states.

### Navigation and context

Discover, My shelf, and Settings use the same Material icon and label pair in navigation bar and rail. In compact detail, an auto-mirrored back control precedes The book or The recording context. A recording chosen from matching results offers Choose another recording; it and System Back return to the catalog details with the source results retained. The player has a collapse control and bookmark action. Full-screen state and destination selection come from the view model, not decorative visual state.

### Book and recording rows and covers

[BookRow](app/src/main/java/app/narrio/ui/CatalogScreens.kt) pairs a cover (76 × 112 dp) with grouped title and author. Catalog rows add a description excerpt of up to three lines and a quiet metadata-provider label; one row represents each normalized title/author identity. Saved recording rows retain narrator, available duration, source/cache context, and a trailing resume action when available. Titles use up to three lines. Discover uses these open rows beneath the page heading, search, and browse categories; the original featured hero and tall cover-tile shelf are historical compositions.

[BookCover.kt](app/src/main/java/app/narrio/ui/BookCover.kt) displays available provider artwork using ContentScale.Fit. While an image loads or when it fails or is absent, it draws the original typographic covers or garden image. Fallback lettering adapts to available width and the large presentation flag, and is omitted below 60 dp width. Preserve [docs/ART.md](docs/ART.md), its generation prompt provenance, and the bundled Newsreader/Manrope SIL Open Font License references.

### Mini-player

The mini-player uses a full-width functional surface, a small cover (38 × 52 dp), title and current-part context, skip, and play/pause. The whole surface opens the player. A thin progress line (2 dp) appears only when duration is known. Buffering replaces transport content with a progress indicator; the text remains tied to actual state.

### Functional containers and modals

RecoveryState pairs a title, readable message, and Try again action inside the container shape with 20 dp padding. Preparation uses the same material and gives a check action plus actual readiness or progress. EmptyState uses a Material illustration icon, title, explanatory text, and an action supplied by the surrounding screen.

Source options and current parts use selection-colored rows with radio or current-part indicators. Source, parts, and bookmarks open in ModalBottomSheet. Speed and sleep use scrollable AlertDialog content. Shelf removal uses an explicit Material confirmation dialog. See [DetailAndSettings.kt](app/src/main/java/app/narrio/ui/DetailAndSettings.kt).

### Book details and cache-aware source selection

Discovery identifies books through metadata without checking audio availability. Catalog details retain the existing large cover, title, author, description, provider attribution, and Save action. Their primary action is **Find sources**, disabled during lookup. Supporting copy explains that book information does not guarantee an audiobook source; disconnected listeners can connect TorBox for more sources. See [CatalogScreens.kt](app/src/main/java/app/narrio/ui/CatalogScreens.kt), [DetailAndSettings.kt](app/src/main/java/app/narrio/ui/DetailAndSettings.kt), and [NarrioViewModel.kt](app/src/main/java/app/narrio/ui/NarrioViewModel.kt).

Find sources adds a Listening sources section within the same scrollable detail pane. Open, divided recording rows group the original release title, narration label, provider, language, available formats, and a primary-colored readiness label. Only matching recordings with usable public or cached audio appear, with cached sources first. Native progress, retry, and No suitable sources found states occupy that section; partial-provider errors remain visible alongside available results. Selecting a row opens recording details, whose **Listen** action opens the existing SourcePicker sheet.

Recording rows add quiet provider/cache context through labelSmall. Detail and source sheets retain visible indexed metadata uncertainty: narration and edition can be unverified, and the listener should inspect the release and files. Do not replace unknown author, language, narrator, or abridgment details with inferred facts. Indexed discovery is not a promise of cache availability or commercial catalog coverage.

SourcePicker remembers an available preferred format, otherwise starts with a cached format when one exists, otherwise the first source. The chosen format and delivery determine readiness: direct Internet Archive delivery is ready, while TorBox requires the chosen format in the recording's cachedFormats. A cached M4B does not make an uncached MP3 ready. Keep the selected source row, format/file count, Cached label when applicable, and delivery state together.

The primary action is Stream now for TorBox, Start listening for direct public audio, or Connect TorBox when an account is needed. Immediate TorBox streaming is disabled for an uncached chosen format while connected. A separate outlined Prepare in TorBox action appears for that case, with explicit waiting context. Details with no discovered indexed audio can separately offer Prepare this release in TorBox or connection guidance. These action conditions live in [DetailAndSettings.kt](app/src/main/java/app/narrio/ui/DetailAndSettings.kt).

### Cloud preparation

Preparation reuses the existing functional tonal container, title, progress indicator, and Check availability action. It shows actual cloud progress, a positive transfer rate when supplied, approximate remaining time when available or ETA unavailable, and optional connected-seed context. The copy distinguishes making a cached source available in the account from fetching an uncached source. Cloud fetching is explicitly separate from saving audio to the phone; the listener can leave and return from the shelf. Do not present cloud progress as phone-storage progress.

### Phone downloads and offline listening

Download to phone is a separate optional outlined action in SourcePicker. It is enabled only when the chosen source/delivery is ready, the app is not busy, TorBox delivery is connected when required, and no download already exists for that recording and format. A completed matching download offers Play offline; an existing incomplete download points to management on the shelf. Known source size appears as Phone storage context. The sheet says streaming does not save the book to the phone.

OfflineStatus appears with the recording on both the shelf and detail pane. It reuses surfaceContainer, the existing container corner and inset, and native Material controls. Its status is paired with format, completed-file count, and known downloaded/total bytes. Phone progress appears only when total size is known. Active downloads offer Pause download, paused downloads offer Resume download, and failed downloads offer Retry download. A completed download offers Play offline. Use the FlowRow actions to retain readable wrapping under system font scaling.

Remove download opens a native confirmation explaining that audio is removed while the book, bookmarks, and listening progress remain on the shelf. Removing the entire shelf recording is a different confirmation that also removes its phone downloads, saved progress, and bookmarks. Keep those consequences explicit.

### Download preference

Settings introduces the existing headlineSmall/bodyMedium/titleSmall hierarchy around a native Switch labeled Download only on Wi-Fi. It is on by default; enabled copy says downloads wait for an unmetered connection, while disabled copy explains that downloads can use mobile data, including large whole-book files. This is a phone-download preference, separate from streaming and cloud preparation. The value is saved on the device and the status remains visible in the same scrollable settings page.

### Appearance

Settings uses a quiet surfaceContainer entry showing the selected palette and mode. The Appearance page puts a live story/control preview before independent mode, two-column palette, font, and text-size choices. Palette selection has a native selectable state, outline, and checkmark; font rows use radio semantics. Changes to these options apply and persist immediately.

The custom editor is a separate scrollable settings page with a preset starter, a name of up to 28 characters, independent Night/Day previews, twelve 48 dp swatches, HSV sliders, and strict six-digit hex input with an optional `#`. Save & use theme remains outside the scrolling controls, and invalid input disables saving. The saveable draft survives activity recreation and reaches the app only through that action; Back cancels it. Preset switching retains the saved custom slot. Restore default appearance returns to Listening room, Night, Narrio fonts, and Default size while retaining that saved slot. See [AppearanceScreen.kt](app/src/main/java/app/narrio/ui/AppearanceScreen.kt).

### Follow-along passages

Follow along is a reading extension of the Listening room, exposed beside Audio through native Material tabs. It keeps the existing Night/Day materials and player frame. [FollowAlongScreen.kt](app/src/main/java/app/narrio/ui/FollowAlongScreen.kt) owns the passage surface; [PlayerScreen.kt](app/src/main/java/app/narrio/ui/PlayerScreen.kt) supplies its heading and compact transport.

Passages use Newsreader through headlineSmall. The current passage gains stronger type (SemiBold) and copper primary color; surrounding passages stay regular and muted through onSurfaceVariant. A line selected for timing adjustment uses sage secondary color and the same stronger weight. Manrope metadata and controls remain subordinate. Open passage rows use generous separation (20 dp), side insets (24 dp), and a minimum touch height (48 dp), rather than individual cards. Current/estimated state descriptions, selection semantics, and native click labels explain what tapping will do.

ListeningState.positionMs drives highlighting and keeps the active passage near the upper quarter of the text viewport. A manual drag suspends following; Back to current line resumes it. Tapping a passage seeks using its displayed timing mode. Adjust timing suspends following while the listener selects the narrated line, then Match at [audio time] saves that line's media-time anchor. Timing between matches remains estimated. Automatic scroll uses an animated transition only when Android animations are enabled; otherwise it moves directly.

Keep timing status visible. Ordinary EPUB/text uses Estimated timing and drift guidance. Supplied timestamps describes a VTT track attached to one source/audio part; it does not verify edition or narration alignment. A track on another part suppresses highlighting and shows a recovery warning. In a single-file recording, the chapter menu makes estimated jumps across the whole recording; an ambiguous multipart layout asks which text chapter belongs to the current part. Do not present these mappings as exact synchronization.

The empty state offers Find book text and Choose a text file. The native document chooser accepts EPUB, UTF-8 TXT, and VTT; the Book text sheet also lists companion files and public Gutenberg results, with title/author/language/format and edition guidance. Saved text stays on the device. Removal uses a Material confirmation explaining that text and timing matches are removed while audio, bookmarks, and listening progress remain.

Reuse the existing compact/expanded and hinge-aware Listening room layout. Text gets the remaining height above the transport; in tabletop posture, text and transport occupy separate panes around the hinge gap. The transport retains a native slider, 30-second skips, buffering state, and a primary play/pause target (56 dp), with standard control gaps (8 dp). Font scaling and Night/Day colors come from the existing Material theme. Full reader navigation and automatic exact alignment remain deferred; see [docs/FOLLOW_ALONG.md](docs/FOLLOW_ALONG.md). The [scoped native extension record](.impeccable/follow-along.design.json) carries this pattern's behavior and evidence without refreshing the global design sidecar.

**The Native State Rule.** Let Material components render pressed, focused, selected, disabled, loading, and modal states. Web panel hover/focus samples do not define Android interaction.

The sidecar's HTML/CSS entries are labeled display samples for the Impeccable panel. They illustrate these implemented primitives; they are not shipped UI, native motion specifications, or browser evidence. Their hover/focus styling and generated tonal ramps exist only to make those samples usable.

## Do's and Don'ts

### Do:

- **Do** use the matching Night or Day Material role for interface text, actions, surfaces, and dividers.
- **Do** reuse semantic typography roles and keep native text sizes in sp.
- **Do** keep palette, mode, font, and text-size choices independent, and apply custom drafts only when saved.
- **Do** derive custom content and control colors from the seeds with the existing contrast generator.
- **Do** keep recording title, author, narrator, language, and source visibly grouped.
- **Do** use 24 dp page padding and the existing compact spacing steps for related metadata.
- **Do** preserve independently scrollable panes and real hinge clearance when the window changes.
- **Do** use Material icons, native controls, accessible action descriptions, and native minimum touch sizing.
- **Do** retain artwork provenance and bundled font licenses in docs/ART.md.
- **Do** show truthful playback, preparation, account, and recovery state.
- **Do** gate immediate TorBox streaming by the selected format and delivery readiness.
- **Do** keep streaming, cloud preparation, and optional phone saving as separate explicit actions.
- **Do** retain indexed metadata uncertainty and approximate or unavailable ETA labels.
- **Do** show phone-download state, recovery actions, offline playback, and the saved unmetered-connection preference.

### Don't:

- **Don't** replace the warm paper scheme with an automatic inversion of the night palette.
- **Don't** apply per-book artwork colors to interface-wide controls or surfaces.
- **Don't** recolor the garden artwork or its fixed cream text and scrim when applying a palette.
- **Don't** wrap every catalog row or text section in another decorative container.
- **Don't** shrink the tabletop cover to a fixed thumbnail when the upper pane has room for the artwork.
- **Don't** place content through a reported separating hinge.
- **Don't** treat synthetic posture screenshots as physical Samsung hardware validation.
- **Don't** treat panel CSS units, hover effects, generated ramps, or font fallbacks as shipped Android behavior.
- **Don't** fabricate listening progress, catalog coverage, account status, or runtime performance claims.
- **Don't** imply that every format is ready because one format is cached.
- **Don't** describe TorBox cloud fetching as audio already saved to the phone.
- **Don't** present synthetic cache or preparation screenshots as successful authenticated TorBox playback.
