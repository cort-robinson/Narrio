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
| displaySmall | Newsreader | 36 / 40 | 400 | Page headings in My shelf and Settings. |
| headlineLarge | Newsreader | 32 / 36 | 400 | Book and recording titles, large cover lettering, and tabletop book context. |
| headlineMedium | Newsreader | 28 / 32 | 400 | Wordmark, player title, sheet/dialog headings, and empty-state titles. |
| headlineSmall | Newsreader | 24 / 28 | 400 | Shelf, description, source, and settings section headings. |
| titleLarge | Newsreader | 22 / 28 | 500 | Regular cover lettering. |
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

[NarrioApp.kt](app/src/main/java/app/narrio/ui/NarrioApp.kt) chooses expanded composition at a current window width of at least 600 dp, except during open tabletop playback. Compact catalog destinations use a three-destination Material navigation bar; an active mini-player sits above it. Detail and full player replace the catalog view on compact windows. Expanded windows shorter than 480 dp (a phone in landscape) give an open player the whole canvas beside the rail.

Expanded composition uses an 80 dp navigation rail. Without a separating vertical hinge, the catalog begins at 53% of the width remaining after the rail; its width is bounded by the source's 250 dp pane minima. The secondary pane displays the selected book or recording, current player, or welcome state. The catalog column shows the mini-player only while details cover the listening room; when the secondary pane is the player, it would repeat it. With a separating vertical hinge, pane positioning uses the reported bounds and leaves a gap at least 1 dp wide. Both content panes scroll independently.

Horizontal HALF_OPENED posture becomes tabletop only while the player or read along is open. The upper pane is bounded by the hinge after subtracting the status inset. Its cover height is the smaller of available height or `upper width × 0.55 × 1.45`; cover width is `height ÷ 1.45`. The context column scrolls within that upper region. The hinge gap is the greater of the reported gap and 12 dp; the lower transport pane scrolls with 24 dp padding. These are current layout calculations, not physical device measurements.

Safe drawing insets protect the top and horizontal edges; full/detail/expanded content also applies navigation-bar padding. Settings applies IME padding. System Back collapses the player, returns a recording chosen from source results to its catalog details, closes details, or returns another destination to Discover. On compact windows the predictive Back gesture scrubs the real screen transition toward that destination before the listener commits.

The listening room restructures for its window rather than scrolling the play control away. Short wide canvases put the cover beside the title and transport; short narrow panes reduce the cover to a byline thumbnail; otherwise the transport is measured first and the cover takes the remaining height (capped at 470 dp), falling back to a scrolling column with a 150–180 dp cover only when that cannot fit. Short windows fold the Read along action into the heading row.

Browse category chips scroll horizontally at compact and expanded widths and disappear while a search query is entered. SourcePicker keeps its content in a vertically scrollable native sheet, with weighted option text that can wrap around its radio controls. Long release names and larger system text expand the sheet content instead of clipping the source and preparation actions. App and sheet system-bar icons use foreground contrast against the actual scheme background, including custom palettes. These behaviors are implemented in CatalogScreens.kt, NarrioApp.kt, and DetailAndSettings.kt; they do not introduce new breakpoints or fixed modal geometry.

**The Window First Rule.** Choose the composition from current window width and WindowManager features. Do not select a layout from the device name or assume physical Fold dimensions.

**The Hinge Is Space Rule.** Keep the reported separating region free of content. In tabletop playback, constrain artwork and book context to the upper pane and let lower controls scroll independently.

The 15 reviewed native captures under [.impeccable/review](.impeccable/review/) include compact and expanded windows, Night and Day, enlarged type, a separating hinge, and tabletop. [finish-verdict.md](.impeccable/review/finish-verdict.md) reports the tabletop correction resolved with ship disposition at that correction's scope. Hinge postures were injected through WindowManager testing; physical Fold 8 geometry, gestures, refresh rate, and performance are not established by these images.

The [1.1 finish review](.impeccable/review/v1.1/finish-review.md) returns ship with no material fixes for the introduced source, cache, preparation, and phone-download regions. Its 12 cached-source, uncached-source, preparation, and offline-settings captures cover phone, phone with enlarged type, and expanded layouts. Availability and cloud progress in those images are synthetic SourceExperienceTest fixtures. Preparation and settings captures intentionally show scrolled regions. The additional [offline release shelf](.impeccable/review/v1.1/offline-shelf-release-phone.png) shows a real downloaded M4B in the final signed release. Offline MP3/M4B playback, seeking, later-part behavior, pause/resume, and cold restart passed native validation. The final signed release additionally passed an offline M4B cold restart; see [offline-native-final.txt](verification/offline-native-final.txt), [release-offline.json](verification/release-offline.json), and [offline-restart.json](verification/offline-restart.json). The user subsequently confirmed the requested live cached-M4B and basic fold/unfold phone flow; see [user-device-1.1.json](verification/user-device-1.1.json). Subsequent direct signed-release checks on the connected Android 17 phone verified live multipart TorBox listening, later-part seeking, background/screen-off continuation, system headset-button events, network recovery, and saved-position reopening; see [physical-android-1.1.json](verification/physical-android-1.1.json). These functional checks do not extend the historical screenshot review into a measured Samsung hinge, Bluetooth-routing, or performance claim. The original review report remains a historical snapshot; exact Samsung hardware geometry and performance are outside these functional checks.

The separate [Appearance finish review](.impeccable/review/appearance/finish-review.md) returns ship with no material fixes for this branch's customization feature. Its 18 named native region captures cover phone, expanded, and enlarged-text windows, including system scaling 1.3 with app Large 1.2. The previews use synthetic story content and a custom Aurora palette. This review and [appearance.json](verification/appearance.json) establish the scoped emulator appearance behavior; they add no signed-release, physical-phone, or audio certification to the historical evidence above.

The metadata-first catalog and Find sources increment is documented from current Compose source. Rendered Android QA remains unverified: the T3 Android device is unavailable, no adb devices are connected, and no screenshots exist for this increment. The historical reviews above remain valid only for their recorded scope; they do not establish the new catalog, matching-recording rows, or return navigation at phone, expanded, Night/Day, or enlarged-text sizes.

## Elevation & Depth

Depth is primarily tonal. Catalog rows sit directly on the ground; dividers separate longer sections. The secondary pane uses surfaceContainerLow, the mini-player and functional status containers use surfaceContainer, and selected rows use secondaryContainer. The mini-player explicitly sets tonal elevation to zero (0 dp). Material sheets and dialogs retain their library elevation behavior; no custom shadow token or elevation scale has been authored.

Atmospheric depth comes from the original garden cover image and its contrast scrim. These are static artwork treatments, not looping effects or a substitute for real media. Retrieved provider art keeps its aspect inside the book-shaped frame; a soft, lightly scrimmed crop of the same art fills the margins instead of flat color bars (a blur on large Android 12+ covers; a tiny upscaled decode for thumbnails and older releases). Thumbnails decode at 240 px; large covers decode at 720 px and show the cached thumbnail until then, so shared-element flights never draw blank. Only standalone covers carry a "Cover of" description; rows and the mini-player already read the title.

The one authored shadow belongs to the listening cover: it leans forward (full scale, 22 dp shadow) while narration plays and settles back (90%, 4 dp) when paused.

**The Quiet Surface Rule.** Use tonal surfaces and outlineVariant dividers to distinguish functional regions. Preserve Material sheet and dialog elevation; apart from the listening cover, the implementation has no custom shadow vocabulary.

## Motion

[Motion.kt](app/src/main/java/app/narrio/ui/Motion.kt) owns the vocabulary: emphasized easing curves, 150/300/450 ms durations, Material fade-through between destinations, shared axis X for deeper content (book → recording → back), and a rise/fall pair for the listening room. Compact navigation runs in a SharedTransitionLayout: a cover travels from its catalog, shelf, or resume row into details, and from the mini-player into the listening room and back. Palette and mode changes dissolve the whole color scheme over 450 ms.

Feedback stays small and physical: the resume card, palette tiles, tool slots, and play control press slightly inward (list rows keep the Material ripple only); the play control morphs from circle (paused) to a softened square (playing); skips (back 10 s, ahead 30 s) turn 30° in their direction; bookmarking pops; Android haptics confirm toggles, skips, bookmarks, and the pull-down threshold. The player can be pulled down to collapse, and the mini-player flicked up to open. Narration bars move only while audio actually plays. Skeleton book rows stand in while metadata loads; read-along narration marks fade in as the narrator moves on.

**The Still Room Rule.** Compose scales every duration by Android's animator setting. Looping motion (narration bars, skeleton light) and read along's narration marks and page follow additionally check that setting and render still when animations are removed.

**The Artwork Contrast Rule.** Keep original artwork and its text scrim together. The gradient over the garden cover is a legibility treatment already present in the build.

Playback publication follows visibility: the service loop runs at one second while visible and five seconds while hidden, publishing in the hidden state while playback or a sleep timer is active. Background audio updates do not literally stop. These intervals appear in [ListeningService.kt](app/src/main/java/app/narrio/playback/ListeningService.kt), with lifecycle visibility set in NarrioApp.kt.

## Shapes

Covers have quietly rounded corners (8 dp). Selection rows and the credential field use a compact curve (12 dp). Search, recovery, and preparation containers use a slightly softer curve (14 dp).

The prominent play control is circular (82 dp) while paused and a 30%-cornered square while playing; its shape reports the state. Other buttons, chips, navigation indicators, sheets, and dialogs use Material defaults because the app does not override MaterialTheme.shapes. Preserve those native silhouettes rather than assigning all controls the container radius.

## Components

### Buttons and transport

Filled primary buttons carry Find sources, Listen, Read, Discover, and Connect actions. Filled tonal buttons support quieter actions, including the second available format on book details. Outlined controls hold Find ebook and Find audiobook; book details carry Save as a labelled top-app-bar text action; the player's speed, sleep, parts, and bookmarks share an even four-slot tool tray of icon-over-label targets that never wraps; text buttons carry retry, read-more, and subordinate actions. Their default colors and interaction states come from Material 3. Source-level enabled conditions express loading, account, and saved state.

Player transport gives the play/pause control a larger target (82 dp, 64 dp on short windows) and skip controls medium targets (56 dp), with real buffering progress inside the primary control. Speed and sleep choices use radio-selectable rows with the current choice highlighted. The slider is disabled until duration is known, exposes the listening-position description, and seeks when dragging finishes. Touching the bar never moves the place (in a ten-hour part a finger's width is most of an hour); dragging is relative to where you were, and the sideways drift of a vertical slide is ignored. Sliding the finger up while seeking slows it in four bands (down to about a second per 10 dp); a card above the finger shows the band, a precision meter, the time and its change, and a time ruler that zooms with the band, with a haptic tick per ruler mark and a threshold tick per band. App snackbars can be swiped sideways to dismiss (TalkBack: Dismiss). See [PreciseSeek.kt](app/src/main/java/app/narrio/ui/PreciseSeek.kt). See [PlayerScreen.kt](app/src/main/java/app/narrio/ui/PlayerScreen.kt).

### Inputs and chips

Search is a single-line OutlinedTextField with a search icon, clear action, and the container corner. The credential field is a password-transformed OutlinedTextField with the selection corner. Native field focus, outlines, keyboard interaction, and disabled state remain Material-owned.

FilterChip drives browse category and Appearance mode, text-size, and custom color-role selection. Chip groups use short gaps (8 dp); categories scroll horizontally and Appearance groups wrap. Category chips appear only with a blank query, and entering a query resets the browse category to All. Discovery has no source-scope chip row. Selected states remain native Material states.

### Navigation and context

Discover, My shelf, and Settings use the same Material icon and label pair in navigation bar and rail. Book details use a pinned Material top app bar titled The book or The recording, with an auto-mirrored back control in compact windows; it tints to surfaceContainer once content scrolls beneath it. A recording chosen from matching results offers Choose another recording; it and System Back return to the catalog details with the source results retained. The player has a collapse control and bookmark action. Full-screen state and destination selection come from the view model, not decorative visual state.

### Book and recording rows and covers

[BookRow](app/src/main/java/app/narrio/ui/CatalogScreens.kt) pairs a cover (76 × 112 dp) with grouped title and author. Catalog rows add a quiet metadata-provider label and leave the publisher description to the details page; one row represents each normalized title/author identity. Saved recording rows retain narrator, available duration, source/cache context, and a trailing resume action when available. Titles use up to three lines. Discover uses these open rows beneath the wordmark, search, and browse categories, with a result count only for a typed query; the original featured hero and tall cover-tile shelf are historical compositions. Continue reading or Continue listening is a tonal resume card for the most recently moved shared place, with that place, progress when known, and a book or play action for the mode used last; a listening card hides while that book is already in the mini-player. Shelf rows add the same progress line from the shared place ("Ch 12 · 43%", "Part 3 of 12 · 20%"), sage format marks (Audiobook, Ebook) without containers, a trailing action for the last mode, narration bars for the playing book, and Remove from shelf in an overflow menu; tapping the row opens its details. All / Audiobooks / Ebooks / Both FilterChips sit under the heading (Audiobooks and Ebooks include pairs), each empty filter explains how to fill it, and Add ebook beside the heading adds an EPUB or TXT file as an ebook-only book.

[BookCover.kt](app/src/main/java/app/narrio/ui/BookCover.kt) displays available provider artwork using ContentScale.Fit. While an image loads or when it fails or is absent, it draws the original typographic covers or garden image. Fallback lettering adapts to available width and the large presentation flag, and is omitted below 60 dp width. Preserve [docs/ART.md](docs/ART.md), its generation prompt provenance, and the bundled Newsreader/Manrope SIL Open Font License references.

### Mini-player

The mini-player uses a full-width functional surface, a small cover (38 × 52 dp), title and current-part context with narration bars while playing, skip, and play/pause. The whole surface opens the player. While a book plays it stays docked at the bottom of every phone screen: above the tabs at home, under a book's details, and under the reader's page (opening it there leaves the reader for the Listening room). Only the Listening room and read along, which carry their own controls, replace it. A thin progress line (2 dp) appears only when duration is known. Buffering replaces transport content with a progress indicator; the text remains tied to actual state.

### Functional containers and modals

RecoveryState pairs a title, readable message, and Try again action inside the container shape with 20 dp padding. Preparation uses the same material and gives a check action plus actual readiness or progress. EmptyState uses a Material illustration icon, title, explanatory text, and an action supplied by the surrounding screen.

Source options and current parts use selection-colored rows with radio or current-part indicators. Source, parts, and bookmarks open in ModalBottomSheet. Speed and sleep use scrollable AlertDialog content. Shelf removal uses an explicit Material confirmation dialog. See [DetailAndSettings.kt](app/src/main/java/app/narrio/ui/DetailAndSettings.kt).

### Book details and cache-aware source selection

Discovery identifies books through metadata without checking audio availability. Catalog details retain the existing large cover, title, author, description, provider attribution, and Save action. Their primary action is **Find sources**, disabled during lookup. Supporting copy explains that book information does not guarantee an audiobook source; disconnected listeners can connect TorBox for more sources. See [CatalogScreens.kt](app/src/main/java/app/narrio/ui/CatalogScreens.kt), [DetailAndSettings.kt](app/src/main/java/app/narrio/ui/DetailAndSettings.kt), and [NarrioViewModel.kt](app/src/main/java/app/narrio/ui/NarrioViewModel.kt).

A catalog book's listening slot is a **Best match** card on surfaceContainer (14 dp corner, 20 dp inset): a copper eyebrow, the plain-language reasons in titleMedium ("Ready to stream · M4B · Read by …", "Free public recording", "Needs preparing in TorBox"), quieter reasons in secondary bodySmall, the release name, its source and "also found by", one primary action (Listen, Prepare in TorBox, or Connect TorBox to listen), then Other choices and Format and download. Reading's slot follows the card, or leads it when reading was the last mode. While sources are still answering the card holds skeleton lines the height of a result and a disabled "Finding audio…", so nothing below moves when the first match lands. The card may improve until the listener first touches or scrolls the page (or from the start with TalkBack); after that a better match waits behind a tonal "Better match found · Show" instead of replacing what they are about to press. A release picked in Listening options leads as "Your choice" with "Use the best match". Without a match the card names the outcome and the next step: No sure match (review possible matches), Couldn't reach any source (Try again), No free public recording found (Connect TorBox), No recording found (Search again), or every source off. Below it, **Listening sources** (headlineSmall, with a labelMedium progress line) lists one divided section per source in the listener's priority order, present from the start: its name and a crossfading status (Searching or Checking TorBox with a small spinner, Waiting its turn, "4 found" in secondary with a check, Nothing for this book, Timed out or Couldn't search in error with its message and Retry, Off in settings or Needs TorBox). A searching section holds one release-sized skeleton. Release rows show the release name (titleSmall), the narration label (secondary), one wrapping line of availability (copper when ready) with format and parts, size, and seeders, and "Also found by …"; the best match is marked. Three rows show before "Show all", and possible matches fold under their own 48 dp toggle with a reminder to check the release and narrator. Choosing a row opens the recording's page, as before. Screen readers hear only milestones (searching, the first best match, the finished search) through a polite live region. Panes at least 700 dp wide put sections in two columns. See [SourceResults.kt](app/src/main/java/app/narrio/ui/SourceResults.kt) and [SourceResultsModel.kt](app/src/main/java/app/narrio/ui/SourceResultsModel.kt); the planning sketch is [.impeccable/source-search/source-search-plan.png](.impeccable/source-search/source-search-plan.png).

Recording rows add quiet provider/cache context through labelSmall. Detail and source sheets retain visible indexed metadata uncertainty: narration and edition can be unverified, and the listener should inspect the release and files. Do not replace unknown author, language, narrator, or abridgment details with inferred facts. Indexed discovery is not a promise of cache availability or commercial catalog coverage.

SourcePicker remembers an available preferred format, otherwise starts with a cached format when one exists, otherwise the first source. The chosen format and delivery determine readiness: direct Internet Archive delivery is ready, while TorBox requires the chosen format in the recording's cachedFormats. A cached M4B does not make an uncached MP3 ready. Keep the selected source row, format/file count, Cached label when applicable, and delivery state together.

The primary action is Stream now for TorBox, Start listening for direct public audio, or Connect TorBox when an account is needed. Immediate TorBox streaming is disabled for an uncached chosen format while connected. A separate outlined Prepare in TorBox action appears for that case, with explicit waiting context. Details with no discovered indexed audio can separately offer Prepare this release in TorBox or connection guidance. These action conditions live in [DetailAndSettings.kt](app/src/main/java/app/narrio/ui/DetailAndSettings.kt).

### Reading and listening formats

Book details show two format slots: the mode used last leads as the filled action, the other available format is tonal, and a missing format becomes an outlined Find ebook or Find audiobook in the same slot. Slots sit side by side only when both labels fit at their natural width; otherwise they stack full width. The shared place and its bar follow, with the other mode's mapped place marked "≈" while estimated (read aloud as "about …, estimated"). The active edition line names format and provenance; with a recording it adds the narration match (matches, partly matches, doesn't match, not checked) and Choose another edition. Find ebook opens a native sheet that works like listening sources: editions on this phone, then a Best match card (surfaceContainerHigh) with its plain-language reasons and one filled **Add and read** action (**Add and read along** from the Listening room), then one section per ebook source with its own status, Retry, and folded possible matches, then ebook websites and a file choice. A better match found after the reader touches the sheet waits behind "Better match found". Tapping a row adds that edition and closes the sheet. Moves between modes larger than the agreed thresholds use the shared Jumped to where you read/listened · Undo snackbar. See [EbookScreens.kt](app/src/main/java/app/narrio/ui/EbookScreens.kt), [PositionFeedback.kt](app/src/main/java/app/narrio/ui/PositionFeedback.kt), and the [scoped review](.impeccable/review/ereader-library/review.md).

### Cloud preparation

Preparation reuses the existing functional tonal container, title, progress indicator, and Check availability action. It shows actual cloud progress, a positive transfer rate when supplied, approximate remaining time when available or ETA unavailable, and optional connected-seed context. The copy distinguishes making a cached source available in the account from fetching an uncached source. Cloud fetching is explicitly separate from saving audio to the phone; the listener can leave and return from the shelf. Do not present cloud progress as phone-storage progress.

### Phone downloads and offline listening

Download to phone is a separate optional outlined action in SourcePicker. It is enabled only when the chosen source/delivery is ready, the app is not busy, TorBox delivery is connected when required, and no download already exists for that recording and format. A completed matching download offers Play offline; an existing incomplete download points to management on the shelf. Known source size appears as Phone storage context. The sheet says streaming does not save the book to the phone.

OfflineStatus appears with the recording on both the shelf and detail pane. It reuses surfaceContainer, the existing container corner and inset, and native Material controls. Its status is paired with format, completed-file count, and known downloaded/total bytes. Phone progress appears only when total size is known. Active downloads offer Pause download, paused downloads offer Resume download, and failed downloads offer Retry download. A completed download offers Play offline. Use the FlowRow actions to retain readable wrapping under system font scaling.

Remove download opens a native confirmation explaining that audio is removed while the book, bookmarks, and listening progress remain on the shelf. Removing the entire shelf recording is a different confirmation that also removes its phone downloads, saved progress, and bookmarks. Keep those consequences explicit.

### Sources & add-ons

Settings → Sources & add-ons is one page for where Narrio looks: **Audiobook sources** (Internet Archive / LibriVox, My TorBox library, TorBox search, and installed audiobook add-ons), **Ebook sources** (This recording's files, My TorBox ebooks, installed ebook add-ons, and Project Gutenberg, followed by ebook websites), **Book info**, then **Add a source** for manifest import. Each row is one toggleable target (titleMedium name, bodySmall kind, labelMedium status) with a native Switch and an overflow menu. Audiobook and ebook source rows carry a 48 dp drag handle; the row lifts onto surfaceContainerHigh with a shadow while dragged and saves its place on release, with haptic ticks as it passes neighbours. The menu and TalkBack custom actions offer Move up and Move down; add-ons also offer Refresh and Remove, and built-in sources have no Remove. Sources that need TorBox say so in copper while it is disconnected, under a notice with Connect TorBox. Confirmations and errors appear in a polite live-region strip under the app bar, wherever the list is scrolled. See [AddonSettings.kt](app/src/main/java/app/narrio/ui/AddonSettings.kt).

### Download preference

Settings introduces the existing headlineSmall/bodyMedium/titleSmall hierarchy around a native Switch labeled Download only on Wi-Fi. It is on by default; enabled copy says downloads wait for an unmetered connection, while disabled copy explains that downloads can use mobile data, including large whole-book files. This is a phone-download preference, separate from streaming and cloud preparation. The value is saved on the device and the status remains visible in the same scrollable settings page.

### Appearance

Settings uses a quiet surfaceContainer entry showing the selected palette and mode. The Appearance page puts a live story/control preview before independent mode, two-column palette, font, and text-size choices. Palette selection has a native selectable state, outline, and checkmark; font rows use radio semantics. Changes to these options apply and persist immediately.

The custom editor is a separate scrollable settings page with a preset starter, a name of up to 28 characters, independent Night/Day previews, twelve 48 dp swatches, HSV sliders, and strict six-digit hex input with an optional `#`. Save & use theme remains outside the scrolling controls, and invalid input disables saving. The saveable draft survives activity recreation and reaches the app only through that action; Back cancels it. Preset switching retains the saved custom slot. Restore default appearance returns to Listening room, Night, Narrio fonts, and Default size while retaining that saved slot. See [AppearanceScreen.kt](app/src/main/java/app/narrio/ui/AppearanceScreen.kt).

### App updates

App updates is an open native Settings section between Appearance and TorBox, inside the existing scrollable page. [UpdateSettings.kt](app/src/main/java/app/narrio/ui/UpdateSettings.kt) retains headlineSmall for its heading, bodyMedium for the fixed installed channel/version and explanation, titleSmall for the automatic control and status, and bodySmall for supporting guidance. Text and controls resolve through the incumbent Material color roles; errors use error and secondary explanations use onSurfaceVariant. The default Newsreader/Manrope pairing and chosen appearance continue unchanged.

The installed app fixes Stable releases or Development previews; there is no channel picker. Automatic updates is on by default, with a native Switch labeled Automatic app updates for accessibility. Automatic downloads wait for an unmetered connection, described as Wi-Fi in the interface. Android 12+ copy explains installation while Narrio is closed and playback is paused, with possible Android confirmation. Older-Android copy instead asks the listener to pause playback and tap Install update, followed by Android confirmation. Turning automatic updates off explicitly disables automatic checks, downloads, and installation while retaining manual checking. Local/debug builds show their installed version and development-tools explanation without updater controls.

Status distinguishes checking, available, downloading with percentage, ready, installing, confirmation required, and up to date. Checking/installing use indeterminate native progress; downloading uses determinate progress. Status and error text have polite live-region semantics. Errors retain reachable Check for updates or Download update actions when their state permits retry. Allow app updates opens Android's per-app installation permission; permission guidance keeps the fixed channel explicit. Ready offers Install update, and pending Android confirmation offers Confirm update. Playing or buffering disables those installation actions and adds pause-playback guidance with shelf/progress reassurance. Busy work disables check/download/permission actions; pending confirmation disables checking.

Tonal actions and the subordinate Check for updates text button retain native minimum touch targets (48 dp). The section uses related-content spacing (12 dp); its FlowRow actions wrap with horizontal gaps (10 dp) and vertical gaps (4 dp), preserving labels at enlarged system text. A manual Download update action is paired with explicit mobile-data guidance. No decorative card, new artwork, palette, typography scale, or motion vocabulary is introduced.

The [App updates finish review](.impeccable/review/app-updates/finish-review.md) and [fix verdict](.impeccable/review/app-updates/finish-verdict.md) record the incumbent-world match and resolved OS-copy/system-bar contrast findings; ship applies to that fix-list scope. The native component fixtures cover compact, expanded, Night/Day, and 1.5× text ready/permission, confirmation, retry/manual-download, and older-Android wording. They are isolated API 36 renders with controlled states; the older-Android copy forces the capability branch off. These images establish component rendering, not the full Settings page, real older-OS installation, physical-phone acceptance, downloaded releases, or completed package replacement.

### Read along

Read along (together mode) makes the reader the listening surface; it replaces the earlier Follow along passage list. [ReadAlong.kt](app/src/main/java/app/narrio/ui/ReadAlong.kt) owns the controls and narration behavior; the page, typography, contents, and chrome remain the reader's. It opens from **Read along** in the Listening room (a tonal action beside one line of supporting copy, where the Audio/Follow along tabs were; short windows fold it into the heading row as an icon) or from the reader's headphones action when the book has a recording that isn't a mismatch. Without an ebook the slot is an outlined **Find the ebook**, which opens the book's Find ebook sheet.

Narration is drawn on the page with the selected palette: the narrated sentence carries a soft copper (primary) wash per line box, blended with the page (multiply on Day, screen on Night) so the ink keeps its contrast; with **Highlight each word** on, the narrated word gets a stronger wash and a 2 dp copper underline. Where the place is only estimated there is no wash and no word mark: a dotted sage (secondary) underline marks the sentence and the status line reads **≈ Estimated place**. Marks fade in over 300 ms on the emphasized-decelerate curve (150 ms for the word); with animations removed they appear without motion. Page follow uses the reader's animated turn only while animations are enabled.

Phone windows dock a 72 dp tray on surfaceContainer under the page, topped by the mini-player's 2 dp copper progress line: a status row (narration bars, part and time, and the sync status, which opens Read along options) above five even slots: speed, −10, a 56 dp morphing play control, +30, and sleep. After a page turn by hand the status gives way to a tonal **Back to narration**, so the way back sits in the player rather than over the page or under a snackbar. App snackbars rise above the tray. Windows at least 600 dp wide show one text column (never a spread while reading along) and a surfaceContainerLow side panel of 300–380 dp, or the pane beyond a reported separating vertical hinge: cover and title, part, slider with "≈" times, transport with a 72 dp play control, speed and sleep, then Newsreader chapters with the narrated chapter in secondaryContainer. Tabletop posture puts text above the hinge and the same controls centred to 560 dp below it, with a Chapters slot; the hinge gap stays empty (at least 12 dp). Rotation and folding keep the narrated sentence on screen.

The reader's own marks keep working while reading along. A user highlight under the narrated sentence stays in its colour with the narration as a thin copper rim; narration marks never take a tap, so a highlight still opens its tray and other text taps play from that sentence. Search, the bookmark ribbon, and the Bookmarks and Highlights tabs behave as in the reader; a search jump pauses following, and the search pill, highlight tray, and snackbars all sit above the read-along tray. The top bar's actions hold Search and the read-along action, with Night/Day staying in the Aa sheet when both are present.

Read along options is a native sheet: the status with what it means, **Highlight each word**, the narration-model download when needed, **Fix the timing** (taps then choose the sentence being heard, marked in sage, and the tray offers **Match at [time]**), **Choose this part's chapter** for multipart recordings, **Reset timing for this part**, **Back to the Listening room**, and **Stop reading along**. The layout plan is [.impeccable/together/together-plan.png](.impeccable/together/together-plan.png); emulator captures in [.impeccable/review/read-along](.impeccable/review/read-along/) use synthetic fixture text and silent audio, with injected postures rather than a physical Fold.

### Reader

The full reader ([ReaderScreen.kt](app/src/main/java/app/narrio/ui/ReaderScreen.kt)) treats the page as the room: book text fills the window on the Appearance ground and ink, with a muted labelSmall running head (chapter) and folio (print page or percentage, and minutes left in the chapter, prefixed "About" until the phone has measured a reading pace). A centre tap brings tonal surfaceContainer bars down and up with the shared emphasized motion; the edges turn pages, as do swipes and volume keys while nothing plays. The top bar holds close, title/author, Contents, an "Aa" typography control, and Night/Day. The bottom bar holds the chapter and page, a copper progress track with chapter ticks whose drag floats a Newsreader preview of the destination, and previous/next chapter actions. After any jump, a tonal "Back to p. N" chip returns to the earlier place.

The typography sheet previews changes on the live page: font cards (Publisher, Narrio, Newsreader, Manrope, Android, OpenDyslexic) with the selection colour, a size stepper, segmented spacing, margins, and Pages/Scroll, switches for justification, hyphenation, publisher styles, and volume keys, then the Appearance palette swatches and mode, which remain the app-wide Appearance settings. Contents uses Newsreader rows with the current chapter in secondaryContainer. Footnotes open in a sheet; images open in a full-screen zoomable view with their caption; links outside the book ask before leaving. Wide, tall landscape windows (an unfolded phone) show a two-page spread. The planning sketch is [.impeccable/reader/reader-chrome-plan.png](.impeccable/reader/reader-chrome-plan.png); emulator captures are in [.impeccable/review/reader](.impeccable/review/reader/) and use synthetic fixture content, not a physical Fold.

Search, highlights, and bookmarks stay out of the way until asked for. Search is a top-bar action: the field replaces the bar, matches stream in grouped by chapter under copper Newsreader heads with Manrope snippets, and wide windows dock the results to the end edge (400 dp) instead of covering the page. Choosing a match closes the list to a tonal pill above the folio (previous, "“query” · 4 of 27", next, end) while the match carries a copper highlight; "Back to" keeps pointing where reading was before the search. Selecting text leads Android's floating toolbar with Highlight and Note, followed by Copy, Share, and installed text actions. Highlights use four named colours with separate Night and Day tints drawn at the navigator's highlight opacity; a note adds an underline. Highlight saves at once and raises a non-modal tray (four 48 dp swatches, Note, Remove with Undo); the note sheet quotes the passage in Newsreader beside its colour bar. A bookmarked page carries a copper ribbon hanging from its top edge; with the controls up an outlined ribbon adds one. The Contents sheet gains Bookmarks and Highlights tabs; bookmarks list both modes in book order with "≈" on whichever place is mapped and still estimated, and the Listening room's sheet shows the same rows time-first. The plan is [.impeccable/reader/annotations-plan.png](.impeccable/reader/annotations-plan.png); captures in [.impeccable/review/annotations](.impeccable/review/annotations/) use synthetic fixtures on an emulator.

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
