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
  day-muted-text: "#56665C"
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

Narrio is a quiet, personal listening room. Warm ink and warm paper grounds give the artwork space, copper makes listening actions easy to find, and sage supports narration and selection. Serif titles carry the story; the sans serif carries the task.

The implemented world uses Material 3 navigation and controls inside an authored type and color theme. Lists remain open on the page; containers identify artwork, recovery, preparation, or selection. Compact windows keep one task in view, while wider windows put discovery and the selected recording or listening session beside each other.

This guide records the current Compose implementation. The surface concept and first-viewport strategy remain in [.impeccable/direction-contract.md](.impeccable/direction-contract.md). FORM provenance is direction seed `73f8a99b`, grounded index 4, catalog revision `c3b204a1eed6`; the contract records the Operate surface and the code-led path. The exact seed output is [.impeccable/concept-seed-evidence.txt](.impeccable/concept-seed-evidence.txt). There is no approved decision comp or available QUALITY BAR card.

**Key Characteristics:**

- Warm ink at night; warm paper by day.
- Newsreader for story titles; Manrope for metadata and controls.
- Artwork is the centerpiece; functional content stays legible around it.
- Material navigation adapts to available window width and reported hinge posture.
- Explicit loading, preparation, empty, selected, and recovery states.
- Cache-aware source selection with separate cloud preparation and phone storage state.

The color, family, and weight tokens above come from [Theme.kt](app/src/main/java/app/narrio/ui/Theme.kt). The [DESIGN.md format](https://raw.githubusercontent.com/google-labs-code/design.md/main/docs/spec.md) supports CSS dimensions, so frontmatter lengths are a baseline display translation: the same numeric scalar expressed in px. Android implementation uses dp for geometry and sp for typography. Exact native values, role mappings, and sample limitations live in [.impeccable/design.json](.impeccable/design.json), under `extensions.android`; never paste the px adapter into Compose.

## Colors

Copper and sage sit against warm neutral grounds. The two themes retain their own contrast pairings rather than deriving one by inversion.

### Primary

- **Copper:** `night-copper` / `day-copper` identify listening actions, transport, progress, and action labels; their `on-copper` partners carry content inside a filled primary control.
- **Copper containers:** the paired container roles are defined by the theme for Material components. They are not a second independent accent.

### Secondary

- **Sage:** the `sage` pair identifies narrator and secondary context.
- **Selected sage:** `selected` / `on-selected` support Material tonal controls and navigation, with explicit selected-row backgrounds in the source picker and audio-parts sheet.

### Neutral

- **Room ground:** `ground` maps to both background and surface; `text` maps to both onBackground and onSurface.
- **Tonal surfaces:** `surface-low`, `surface`, and `surface-high` separate the rail-adjacent listening pane, navigation, mini-player, sheets, and recovery or preparation containers.
- **Muted ink:** `muted-text` carries explanatory text and metadata.
- **Outlines:** `outline` serves Material outlined controls; `outline-muted` serves dividers and the mini-player progress track.
- **Error:** the explicit Night error pair remains part of the theme. Day inherits the Material light scheme's default error roles; no custom Day error palette was extracted.

Featured artwork is a component-specific exception: its cream text, dark green scrim, and Night copper action remain fixed over the garden in both themes. Per-book cover palettes belong to artwork, not global interface tokens.

**The Paired Scheme Rule.** Resolve interface colors through MaterialTheme.colorScheme. Night and Day are complete authored schemes, selected explicitly or through System; the current app does not use wallpaper-derived dynamic color.

## Typography

**Display Font:** bundled Newsreader variable font.

**Body and Control Font:** bundled Manrope variable font.

Newsreader is expressive at normal weight; Manrope gives metadata and actions an even, clear rhythm. Theme.kt supplies a complete Material role table. The frontmatter captures roles used by current screens or their Material controls; displayLarge and displayMedium are declared in the native theme but have no current screen application.

| Native role | Family | Size / line height (sp) | Weight | Typical use |
| --- | --- | --- | --- | --- |
| displaySmall | Newsreader | 36 / 40 | 400 | Repeated page headings in Discover, My shelf, and Settings. |
| headlineLarge | Newsreader | 32 / 36 | 400 | Recording titles, large cover lettering, and tabletop book context. |
| headlineMedium | Newsreader | 28 / 32 | 400 | Wordmark, player title, sheet/dialog headings, and empty-state titles. |
| headlineSmall | Newsreader | 24 / 28 | 400 | Shelf, description, source, and settings section headings. |
| titleLarge | Newsreader | 22 / 28 | 500 | Regular cover lettering and the quiet player closing line. |
| titleMedium | Manrope | 16 / 23 | 600 | Recording rows, context bars, preparation state, and bookmark times. |
| titleSmall | Manrope | 14 / 20 | 600 | Shelf titles, source options, parts, and account status. |
| bodyLarge | Manrope | 16 / 26 | 400 | Recording author and the Material text-field body role. |
| bodyMedium | Manrope | 14 / 22 | 400 | Descriptions, instructions, and supporting context. |
| bodySmall | Manrope | 12 / 19 | 400 | Author, narrator, source, and playback metadata. |
| labelLarge | Manrope | 14 / 20 | 600 | Material action labels. |
| labelMedium | Manrope | 12 / 18 | 600 | Elapsed/remaining times, metadata, preparation labels, and chips. |
| labelSmall | Manrope | 11 / 16 | 500 | Cover authors, saved progress, and duration captions. |

All Newsreader roles use slightly tight tracking (-0.4 sp); Manrope roles use neutral tracking (0 sp). The bundled variable font weight axis is set per declared weight. There is no uppercase eyebrow or monospace label system.

**The Two Voices Rule.** Use semantic Material typography roles. Newsreader owns display, headline, and titleLarge; Manrope owns the remaining titles, body, labels, and controls. Keep system font scaling in sp.

## Layout

The usual page inset is generous (24 dp). Discover uses a slightly tighter top inset (20 dp) and a lower inset (28 dp). Related metadata uses shorter gaps (4, 8, or 12 dp); recording rows separate art and text (16 dp). Section and page groups commonly use 24 or 28 dp. Component-specific gaps also occur; the build is not a strict universal grid.

[NarrioApp.kt](app/src/main/java/app/narrio/ui/NarrioApp.kt) chooses expanded composition at a current window width of at least 600 dp, except during open tabletop playback. Compact catalog destinations use a three-destination Material navigation bar; an active mini-player sits above it. Detail and full player replace the catalog view on compact windows.

Expanded composition uses an 80 dp navigation rail. Without a separating vertical hinge, the catalog begins at 53% of the width remaining after the rail; its width is bounded by the source's 250 dp pane minima. The secondary pane displays the selected recording, current player, or welcome state. With a separating vertical hinge, pane positioning uses the reported bounds and leaves a gap at least 1 dp wide. Both content panes scroll independently.

Horizontal HALF_OPENED posture becomes tabletop only while the player is open. The upper pane is bounded by the hinge after subtracting the status inset. Its cover height is the smaller of available height or `upper width × 0.55 × 1.45`; cover width is `height ÷ 1.45`. The context column scrolls within that upper region. The hinge gap is the greater of the reported gap and 12 dp; the lower transport pane scrolls with 24 dp padding. These are current layout calculations, not physical device measurements.

Safe drawing insets protect the top and horizontal edges; full/detail/expanded content also applies navigation-bar padding. Settings applies IME padding. System Back is handled in the app to return from player/detail or to Discover.

The source-scope chips now scroll horizontally at compact and expanded widths. SourcePicker keeps its content in a vertically scrollable native sheet, with weighted option text that can wrap around its radio controls. Long release names and larger system text expand the content instead of clipping the source and preparation actions. The sheet also sets its own system-bar icon appearance from the active theme. These behaviors are implemented in DetailAndSettings.kt; they do not introduce new breakpoints or fixed modal geometry.

**The Window First Rule.** Choose the composition from current window width and WindowManager features. Do not select a layout from the device name or assume physical Fold dimensions.

**The Hinge Is Space Rule.** Keep the reported separating region free of content. In tabletop playback, constrain artwork and book context to the upper pane and let lower controls scroll independently.

The 15 reviewed native captures under [.impeccable/review](.impeccable/review/) include compact and expanded windows, Night and Day, enlarged type, a separating hinge, and tabletop. [finish-verdict.md](.impeccable/review/finish-verdict.md) reports the tabletop correction resolved with ship disposition at that correction's scope. Hinge postures were injected through WindowManager testing; physical Fold 8 geometry, gestures, refresh rate, and performance are not established by these images.

The [1.1 finish review](.impeccable/review/v1.1/finish-review.md) returns ship with no material fixes for the introduced source, cache, preparation, and phone-download regions. Its 12 cached-source, uncached-source, preparation, and offline-settings captures cover phone, phone with enlarged type, and expanded layouts. Availability and cloud progress in those images are synthetic SourceExperienceTest fixtures. Preparation and settings captures intentionally show scrolled regions. The additional [offline release shelf](.impeccable/review/v1.1/offline-shelf-release-phone.png) shows a real downloaded M4B in the final signed release. Offline MP3/M4B playback, seeking, later-part behavior, pause/resume, and cold restart passed native validation. The final signed release additionally passed an offline M4B cold restart; see [offline-native-final.txt](verification/offline-native-final.txt), [release-offline.json](verification/release-offline.json), and [offline-restart.json](verification/offline-restart.json). Successful live TorBox cached playback and physical Samsung Fold behavior remain unverified.

## Elevation & Depth

Depth is primarily tonal. Catalog rows sit directly on the ground; dividers separate longer sections. The secondary pane uses surfaceContainerLow, the mini-player and functional status containers use surfaceContainer, and selected rows use secondaryContainer. The mini-player explicitly sets tonal elevation to zero (0 dp). Material sheets and dialogs retain their library elevation behavior; no custom shadow token or elevation scale has been authored.

Atmospheric depth comes from the original garden image and the contrast scrims in the feature and covers. These are static artwork treatments, not looping effects or a substitute for real media. No custom decorative animation duration or easing token exists.

**The Quiet Surface Rule.** Use tonal surfaces and outlineVariant dividers to distinguish functional regions. Preserve Material sheet and dialog elevation; the implementation has no custom shadow vocabulary.

**The Artwork Contrast Rule.** Keep original artwork and its text scrims together. The gradients in the featured scene and image covers are legibility treatments already present in the build.

Playback publication follows visibility: the service loop runs at one second while visible and five seconds while hidden, publishing in the hidden state while playback or a sleep timer is active. Background audio updates do not literally stop. These intervals appear in [ListeningService.kt](app/src/main/java/app/narrio/playback/ListeningService.kt), with lifecycle visibility set in NarrioApp.kt.

## Shapes

Covers have quietly rounded corners (8 dp). Selection rows and the credential field use a compact curve (12 dp). Search, recovery, and preparation containers use a slightly softer curve (14 dp). The featured artwork's corner (16 dp) is component-specific and is not promoted into a general shape token.

The prominent play control is a true CircleShape (82 dp). Other buttons, chips, navigation indicators, sheets, and dialogs use Material defaults because the app does not override MaterialTheme.shapes. Preserve those native silhouettes rather than assigning all controls the container radius.

## Components

### Buttons and transport

Filled primary buttons carry Listen, Discover, and Connect actions. Filled tonal buttons support quieter actions. Outlined controls hold Save, speed, sleep, and parts; text buttons carry retry, read-more, and subordinate actions. Their default colors and interaction states come from Material 3. Source-level enabled conditions express loading, account, and saved state.

Player transport gives the circular play/pause control a larger target (82 dp) and skip controls medium targets (56 dp), with real buffering progress inside the primary control. The slider is disabled until duration is known, exposes the listening-position description, and seeks when dragging finishes. See [PlayerScreen.kt](app/src/main/java/app/narrio/ui/PlayerScreen.kt).

### Inputs and chips

Search is a single-line OutlinedTextField with a search icon, clear action, and the container corner. The credential field is a password-transformed OutlinedTextField with the selection corner. Native field focus, outlines, keyboard interaction, and disabled state remain Material-owned.

FilterChip drives category, source scope when connected, and Night/Day/System selection. Chip groups use short gaps (8 dp); categories scroll horizontally. Their selected states remain native Material states.

### Navigation and context

Discover, My shelf, and Settings use the same Material icon and label pair in navigation bar and rail. In compact detail, an auto-mirrored back control precedes the recording context. The player has a collapse control and bookmark action. Full-screen state and destination selection come from the view model, not decorative visual state.

### Recording rows and covers

[BookRow](app/src/main/java/app/narrio/ui/CatalogScreens.kt) pairs a cover (76 × 112 dp) with grouped title, author, narrator, and available duration; a trailing action can resume a saved recording. The featured shelf uses taller cover tiles (140 × 204 dp) and separate captions. Text truncation is bounded by role and context.

[BookCover.kt](app/src/main/java/app/narrio/ui/BookCover.kt) draws the original typographic covers or displays the original garden image; non-curated recordings can display provider artwork. Cover lettering adapts to available width and the large presentation flag, and is omitted below 60 dp width. Preserve [docs/ART.md](docs/ART.md), its generation prompt provenance, and the bundled Newsreader/Manrope SIL Open Font License references.

### Mini-player

The mini-player uses a full-width functional surface, a small cover (38 × 52 dp), title and current-part context, skip, and play/pause. The whole surface opens the player. A thin progress line (2 dp) appears only when duration is known. Buffering replaces transport content with a progress indicator; the text remains tied to actual state.

### Functional containers and modals

RecoveryState pairs a title, readable message, and Try again action inside the container shape with 20 dp padding. Preparation uses the same material and gives a check action plus actual readiness or progress. EmptyState uses a Material illustration icon, title, explanatory text, and an action supplied by the surrounding screen.

Source options and current parts use selection-colored rows with radio or current-part indicators. Source, parts, and bookmarks open in ModalBottomSheet. Speed and sleep use scrollable AlertDialog content. Shelf removal uses an explicit Material confirmation dialog. See [DetailAndSettings.kt](app/src/main/java/app/narrio/ui/DetailAndSettings.kt).

### Source scope and cache-aware selection

Connected discovery starts with **Ready to stream**. Its horizontally scrolling FilterChip row also exposes All sources, Public books, and My TorBox; disconnected discovery exposes Public books and All sources. Category chips apply to Public books. Keep partial-source notices and errors visible alongside any available results, and let the empty ready-source state offer See all sources. See [CatalogScreens.kt](app/src/main/java/app/narrio/ui/CatalogScreens.kt) and the source-scope default in [NarrioViewModel.kt](app/src/main/java/app/narrio/ui/NarrioViewModel.kt).

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


**The Native State Rule.** Let Material components render pressed, focused, selected, disabled, loading, and modal states. Web panel hover/focus samples do not define Android interaction.

The sidecar's HTML/CSS entries are labeled display samples for the Impeccable panel. They illustrate these implemented primitives; they are not shipped UI, native motion specifications, or browser evidence. Their hover/focus styling and generated tonal ramps exist only to make those samples usable.

## Do's and Don'ts

### Do:

- **Do** use the matching Night or Day Material role for interface text, actions, surfaces, and dividers.
- **Do** reuse semantic typography roles and keep native text sizes in sp.
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
- **Don't** wrap every catalog row or text section in another decorative container.
- **Don't** shrink the tabletop cover to a fixed thumbnail when the upper pane has room for the artwork.
- **Don't** place content through a reported separating hinge.
- **Don't** treat synthetic posture screenshots as physical Samsung hardware validation.
- **Don't** treat panel CSS units, hover effects, generated ramps, or font fallbacks as shipped Android behavior.
- **Don't** fabricate listening progress, catalog coverage, account status, or runtime performance claims.
- **Don't** imply that every format is ready because one format is cached.
- **Don't** describe TorBox cloud fetching as audio already saved to the phone.
- **Don't** present synthetic cache or preparation screenshots as successful authenticated TorBox playback.
