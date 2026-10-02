# Narrio

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Kotlin, Jetpack Compose, Media3 background playback, and Room local persistence, as requested in the supplied brief. Build and design decisions are delegated by the user's request to build and iterate to an installable v1.

## Users

An Android listener who wants to discover narrated books and keep listening across the cover and unfolded displays of a Samsung Galaxy Z Fold 8.

## Product Purpose

Search for an audiobook recording, identify its narrator and language when verified, select an already cached source, and stream it through TorBox without downloading the book to the phone. Offer explicit phone downloads for offline listening. Preserve progress, a personal shelf, bookmarks, playback speed, and a sleep timer on the device.

## Operating Context

Direct connections to catalog and delivery providers; no custom backend. The user will connect TorBox in the app. A TorBox API key is not available for development account testing.

## Capabilities and Constraints

- Catalog, recording discovery, delivery, playback, and library are separate modules.
- Recording and part identifiers own progress; temporary URLs do not.
- V1 needs source-backed discovery, single-file and multipart playback, preparation state, background media controls, and local persistence.
- Fold layouts must use current window size and actual hinge/posture information, preserve state across folding, support multi-window, and honor system insets.
- Working installation package and honest validation evidence are required.
- Discovery combines Knaben's audiobook release index, Internet Archive's LibriVox catalog, and the user's TorBox audio. Indexed release metadata remains visibly unverified; files and TorBox cache availability determine playability.
- Connected search defaults to ready sources. Uncached cloud preparation and phone downloads require separate explicit actions. Phone downloads use Wi-Fi by default and can be paused, resumed, retried, or removed.

## Brand Commitments

Narrio. The user asks for a special and impressive UI/UX and delegates aesthetic decisions.

## Evidence on Hand

The supplied architecture brief is at C:/Users/cortr/.codex/attachments/75da23ca-e27c-428b-b83e-85a017da7589/pasted-text-1.txt. The implemented native app has emulator evidence for real public audio, offline downloads, playback persistence, and injected fold postures. The user installed 1.0, connected TorBox, selected M4B, and reported an uncached source preparing with a one-hour dashboard ETA. Successful live TorBox playback and physical Fold behavior remain unverified. The user's later cache-first and phone-download instructions supersede the brief's original offline-download deferral.

## Product Principles

- Start from real playable recordings.
- Keep uncertain recording details visible.
- Preserve a listener's place and control across screen changes.
- Keep credentials and listening history local.
- Earn visual quality through thoughtful content and native interaction.

## Accessibility & Inclusion

Honor font scaling, system motion settings, screen-reader descriptions, minimum 48 dp touch targets, and high-contrast text. Implement dark and light reading environments.
