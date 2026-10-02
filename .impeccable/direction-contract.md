# Narrio design

The user delegates visual decisions and iteration. This session builds directly in Compose with an explicit direction contract; no persistent build-path preference is inferred.

## Listening room

FORM provenance: direction seed `73f8a99b`, Operate mode, assigned grounded index 4. The original script output is preserved in `.impeccable/concept-seed-evidence.txt` (catalog revision `c3b204a1eed6`). This is a code-led native build with no approved comp.

Grounded directions considered: private-press book jackets, radio programme guides, archival library folios, a listening room, train-window travel journals, poetry anthologies, and analog record sleeves. The design seed assigned the fourth direction: a listening room. It turns the active recording into the centerpiece while the library remains within reach.

Warm ink surfaces, cream Newsreader serif headlines, Manrope controls, copper actions, and sage secondary accents. In daylight, use warm paper rather than an inverted dark theme. Native Material navigation, sheets, controls, and semantic type roles carry the interface. Authored book covers are typographic compositions; atmospheric artwork belongs to the home feature and the listening canvas.

First viewport: Narrio's small wordmark, a large 'Your next chapter.' heading, one evocative featured recording with an immediately usable action, and a varied shelf of real recordings. No fake activity, fabricated metrics, or pretend progress.

Listener path: discover, inspect the narrated edition, choose direct Archive or TorBox delivery, listen, bookmark, and return to the saved shelf. Errors offer retry; preparation stays saved.

Signature interaction: unfolding reveals a second pane containing the selected recording or current listening session without losing the catalog, position, or search. A horizontal half-open hinge divides artwork from reachable transport controls.

## Quality challenges and raises

- Emission-line rail: declined on audience and clarity; retain its disciplined alignment and unambiguous state labels.
- Streaming poster wall: competitive for discovery, loses the listening-room intimacy; retain strong artwork and mixed shelf density.
- Orienteering map: declined on audience and clarity; retain explicit source-to-playback wayfinding.
- Gridded type specimen: declined on audience and clarity; retain typographic ambition and consistent spacing.
- Design annual: competitive for editorial craft, less clear for playback; retain precise cover/caption relationships.
- Garden guide map: declined on task clarity and mature audience fit; retain total palette commitment.

## Tokens and behavior

- Night: background #101B1A, surface #1B2A27, raised #263832, text #F4EDDE, secondary text #B9C8BD, action #E8AF79.
- Day: background #F6F0E5, surface #EEE5D5, text #20332C, secondary text #56665C, action #805031.
- Newsreader for display/headline roles; Manrope for body, label, and controls. Use sp and semantic roles.
- Material 48 dp minimum controls; generous page margins, tight metadata groupings, 14 dp general shapes, cover corners 8 dp.
- Cover screen: three-destination bottom navigation, compact mini-player, vertically scrollable detail/player.
- Unfolded: 80 dp navigation rail, independently scrollable catalog and detail/listening panes; avoid occluding/separating vertical hinges.
- Half-open horizontal posture: artwork and book context above hinge, playback and parts below hinge.
- System font scaling, TalkBack, keyboard, predictive back, edge-to-edge, and reduced motion remain native guarantees.
- Static art and bounded drawing: no constantly running decorative animations. Playback updates stop when UI is not visible.

## Verification

Capture native emulator screenshots for compact, expanded, dark/light, and enlarged fonts. Fix the observed defects in a batch and confirm once. Functional testing can continue for new failures without repeating aesthetic polishing.
