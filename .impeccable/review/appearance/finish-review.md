# Appearance finish review

disposition: ship

Scope: the new native Android appearance feature and its app-wide theme integration. Reviewed the supplied source, product/design direction, surface brief, native craft references, verification logs, and all 18 required PNGs. No app code, device state, build, or capture was changed for this review. No approved comp or QUALITY BAR card exists for this established-world extension; no browser/CSS detector ran because this is native Compose.

## persistence

The feature extends the existing Listening room identity. Its preview, serif default headings, quiet tonal surfaces, Material controls, and existing compact navigation/expanded rail carry that identity without introducing a separate visual world.

Local persistence is concrete: `AppearanceStore.kt:8-11` reads the legacy mode when necessary and saves the complete normalized appearance under `appearance.v1`. `NarrioViewModel.kt:245-248` publishes the saved value. Palette, font, and text-size changes are independent; preset switches and default reset retain the saved custom slot (`AppearanceScreen.kt:87-89, 109-126, 133-134`). Draft state uses a saveable serializer and explicit save/cancel (`AppearanceScreen.kt:198-267`). The native tests verify recreation, store round-trip, legacy migration, cancellation, preset switching, and reset; the supplied logs pass four tests on phone, four on expanded, and the two interaction workflows at system font scale 1.3.

The following documentation handoff should record this scoped extension: five paired presets, the single named custom slot and separate Day/Night seeds, contrast-derived Material roles, four font choices, additive 1.0/1.1/1.2 text scaling, and save/cancel/reset behavior. Update the documented Day secondary ink from `#56665C` to the implemented `#536359` (`Theme.kt:36`), and record the added surface/inverse roles. Preserve the existing artwork, listening, and posture guidance.

## fidelity

The supplied phone captures are 1080×2400, expanded captures 1848×2448, and enlarged-text captures use the phone geometry with system scaling 1.3. All 18 files were opened and show the named native region, with settled content and no blank, loading, or corrupt state. These are scroll-position window captures, not full-page claims; clipping at the scroll viewport boundaries is ordinary scrolling.

- `appearance-day-*` and `appearance-night-*` show a readable preview, mode selection, palette entry, and theme-consistent system/navigation bars. Ocean selection is visible through both its checkmark and outline.
- `palettes-night-expanded.png` shows all five authored palettes and the custom entry; their background, heading ink, and paired accent samples distinguish the choices. The phone captures retain the same two-column selection layout.
- `typography-expanded.png` and `typography-large-text.png` show all four font choices and all three size chips. The enlarged labels, descriptions, and reset action remain readable; the source uses semantic type roles in `sp` (`Theme.kt:50-61`).
- `custom-preview-*` separates the Day draft preview from the currently applied Night editor. `custom-colours-*` shows the role chips, twelve swatches, HSV controls, complete hex value, and persistent Save & use theme action. At enlarged text the Background chip wraps to another row without truncation. The save action stays above existing navigation.

The custom editor does not apply its draft to the app until saving. Contrast adjustment is explained beside the controls and is implemented for semantic foregrounds and surfaces (`Theme.kt:65-99`); the native test checks preset and extreme custom Material pairs. The full test logs and source corroborate invalid-hex disabling and correction through a swatch. Synthetic preview wording makes no account or playback claim.

## ceiling

This was a fresh, bounded review of the provided final evidence and relevant source. No additional capture round, build, device operation, aesthetic iteration, or unrelated defect hunt is required. The verdict covers the scoped appearance feature in the supplied emulator matrix; it does not certify physical-device gestures or runtime performance.

## material_fixes

None. No material visual, interaction, persistence, or accessibility defect was confirmed within the reviewed change. No recapture or rebuild is owed. Proceed to the scoped documentation handoff.

## keep

- Keep the five paired presets and the independent mode/font/text-size choices; the set offers useful range without adding a large settings hierarchy.
- Keep native selectable states, wrapping chip groups, 48 dp colour swatches, Material inputs, and semantic typography.
- Keep the contained live draft preview, precise hex input, preset starting point, explicit save/cancel, and preservation of the saved custom palette during preset switches and reset.
- Keep the expanded listening pane and existing artwork context. Appearance remains part of Settings while the listening experience stays available.
