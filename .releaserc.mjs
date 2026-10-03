import { commitOptions, notesOptions } from './scripts/release/options.mjs';

export default {
  branches: ['master'],
  tagFormat: 'v${version}',
  plugins: [
    ['@semantic-release/commit-analyzer', commitOptions],
    ['@semantic-release/release-notes-generator', notesOptions],
    './scripts/release/android-plugin.mjs',
    ['@semantic-release/github', {
      draftRelease: true,
      assets: [
        { path: 'artifacts/Narrio-${nextRelease.version}.apk', label: 'Signed Android APK' },
        { path: 'artifacts/release-manifest.json', label: 'Version, signing, and source details' },
        { path: 'artifacts/SHA256SUMS', label: 'SHA-256 checksums' },
      ],
      successComment: false,
      failComment: false,
      failTitle: false,
      releasedLabels: false,
    }],
  ],
};
