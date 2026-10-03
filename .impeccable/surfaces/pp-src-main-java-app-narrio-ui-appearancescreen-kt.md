---
version: 1
slug: "pp-src-main-java-app-narrio-ui-appearancescreen-kt"
primary_target: "app/src/main/java/app/narrio/ui/AppearanceScreen.kt"
related_targets: ["app/src/main/java/app/narrio/ui/Theme.kt","app/src/main/java/app/narrio/ui/DetailAndSettings.kt"]
---

# Appearance

Mode: Operate. Extend Narrio's existing Listening room identity with user-selected palettes and typography.

Scope: Settings entry, appearance page, custom-theme editor, app-wide semantic colour and type roles, and local preferences. Preserve artwork and listening behaviour.

Task: choose among five paired Day/Night palettes or author one named custom palette. Keep font and text size independent. Give users a preview, colour swatches, HSV controls, precise hex input, explicit save/cancel, and a reversible default reset.

First viewport: native back navigation, a short introduction, a preview of story text and controls, then Day/Night/System choices. Palette options expose their ground, accents, and title lettering. Custom editing has its own scrollable page and a persistent save action.

Constraints: Material roles, minimum 48 dp controls, device text scaling, small and expanded panes, system Back, readable text and system bars. Drafts survive recreation. Switching presets retains the saved custom palette. No new imagery, network settings, or account access.

Verification: native phone, enlarged-text phone, and expanded captures; local persistence and contrast checks. No approved comp or new visual-world selection is needed for this extension.
