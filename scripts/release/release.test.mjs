import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { git, increment, planRelease, versionCode } from './plan.mjs';
import { certificateDigest, verifyManifest } from './android.mjs';
import { recoveryAction } from './publish.mjs';
import releaseConfiguration from '../../.releaserc.mjs';
import globAssets from '../../node_modules/@semantic-release/github/lib/glob-assets.js';

test('certificate verification supports SDK signer labels while rejecting mixed identities', () => {
  const digest = 'abcdef12'.repeat(8);
  for (const label of ['Signer #1', 'Signer #1 (minSdkVersion=28, maxSdkVersion=2147483647)', 'Signer (minSdkVersion=28, maxSdkVersion=2147483647) #1']) {
    assert.equal(certificateDigest(`${label} certificate SHA-256 digest: ${digest}\n${label} public key SHA-256 digest: ${'1'.repeat(64)}`), digest);
  }
  assert.throws(() => certificateDigest('No certificate was printed'));
  assert.throws(() => certificateDigest(`certificate SHA-256 digest: ${digest}\ncertificate SHA-256 digest: ${'2'.repeat(64)}`));
});

test('GitHub plugin resolves the configured versioned APK asset', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'narrio-release-test-'));
  try {
    mkdirSync(join(directory, 'artifacts'));
    writeFileSync(join(directory, 'artifacts/Narrio-1.2.0.apk'), 'APK fixture');
    const github = releaseConfiguration.plugins.find(plugin => Array.isArray(plugin) && plugin[0] === '@semantic-release/github')[1];
    const assets = await globAssets({ cwd: directory, nextRelease: { version: '1.2.0' } }, [github.assets[0]]);
    assert.deepEqual(assets.map(asset => asset.path), ['artifacts/Narrio-1.2.0.apk']);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('Android version codes increase across patch, minor, and major releases', () => {
  assert.equal(versionCode('1.2.0'), 1_002_000);
  for (const [older, newer] of [['1.2.0', '1.2.1'], ['1.2.999', '1.3.0'], ['1.999.999', '2.0.0']]) {
    assert.ok(versionCode(newer) > versionCode(older));
  }
  for (const invalid of ['01.2.0', '1.1000.0', '1.2.1000', '2101.0.0', '1.2.0-dev.1']) assert.throws(() => versionCode(invalid));
});

for (const [message, expectedVersion, section] of [
  ['fix(player): retain the listening position', '1.1.1', 'Bug fixes'],
  ['perf(search): show cached books faster', '1.1.1', 'Performance'],
  ['feat(metadata): show audiobook descriptions', '1.2.0', 'Features'],
  ['refactor!: change the library storage format\n\nBREAKING CHANGE: Export the shelf before upgrading.', '2.0.0', 'BREAKING CHANGES'],
  ['feat!: remove the old settings format', '2.0.0', 'BREAKING CHANGES'],
  ['docs: clarify installation', null, null],
  ['test: add source coverage', null, null],
  ['chore(ci): configure releases', null, null],
]) {
  test(`release planning: ${message.split('\n')[0]}`, async () => {
    const directory = mkdtempSync(join(tmpdir(), 'narrio-release-test-'));
    try {
      git(['init', '--initial-branch=master'], directory);
      git(['config', 'user.name', 'Release tests'], directory);
      git(['config', 'user.email', 'release-tests@example.invalid'], directory);
      git(['remote', 'add', 'origin', 'https://github.com/cort-robinson/Narrio.git'], directory);
      writeFileSync(join(directory, 'README.md'), 'baseline\n');
      git(['add', 'README.md'], directory);
      git(['commit', '-m', 'chore: baseline'], directory);
      git(['tag', 'v1.1.0'], directory);
      git(['commit', '--allow-empty', '-m', message], directory);
      const plan = await planRelease(directory);
      assert.equal(plan.version, expectedVersion);
      if (section) assert.ok(plan.notes.includes(section), plan.notes);
      else assert.equal(plan.notes, '');
    } finally { rmSync(directory, { recursive: true, force: true }); }
  });
}

test('release recovery never substitutes a newer source for an unpublished tag', () => {
  const state = { version: '1.2.0', baseline: '1.1.0', tagCommit: 'original', head: 'original', published: false };
  assert.equal(recoveryAction(state), 'recover');
  assert.throws(() => recoveryAction({ ...state, head: 'newer' }), /Re-run that release workflow/);
  assert.equal(recoveryAction({ ...state, published: true, head: 'newer' }), 'continue');
  assert.equal(recoveryAction({ ...state, version: '1.1.0', head: 'newer' }), 'continue');
});

test('release manifest rejects mismatched package, version, source, and Android code', () => {
  const manifest = { version: '1.2.0', versionCode: versionCode('1.2.0'), applicationId: 'app.narrio', sourceCommit: 'source', sha256: 'a'.repeat(64), certificateSha256: 'b'.repeat(64), apk: 'Narrio-1.2.0.apk', bytes: 100 };
  const expected = { version: '1.2.0', sourceCommit: 'source' };
  verifyManifest(manifest, expected);
  for (const invalid of [{ applicationId: 'app.narrio.dev' }, { version: '1.2.1' }, { sourceCommit: 'other' }, { versionCode: 3 }, { sha256: 'invalid' }, { apk: 'other.apk' }]) {
    assert.throws(() => verifyManifest({ ...manifest, ...invalid }, expected));
  }
});

test('version bounds cannot silently wrap when calculating the next release', () => {
  assert.equal(increment('1.2.0', 'minor'), '1.3.0');
  assert.throws(() => increment('1.2.999', 'patch'));
});
