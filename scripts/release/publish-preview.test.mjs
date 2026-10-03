import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { previewContext, loadPreview, ciStatus, previewBody, publishPreview, updatePreviewStatus } from './publish-preview.mjs';

const env = { GITHUB_EVENT_NAME: 'push', GITHUB_REF: 'refs/heads/dev', GITHUB_RUN_NUMBER: '53',
  GITHUB_RUN_ID: '123456', GITHUB_SHA: 'a'.repeat(40), GITHUB_REPOSITORY: 'cort-robinson/Narrio' };
const context = previewContext(env);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function fixture() {
  const apk = Buffer.from('signed APK fixture');
  const manifest = { version: '1.4.2-dev.53', versionCode: 53, applicationId: 'app.narrio.dev',
    apk: 'Narrio-1.4.2-dev.53.apk', sourceCommit: context.source, workflowRun: context.runUrl,
    sha256: hash(apk), certificateSha256: 'b'.repeat(64), bytes: apk.length };
  const metadata = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);
  return { manifest, assets: new Map([[manifest.apk, apk], ['release-manifest.json', metadata],
    ['SHA256SUMS', Buffer.from(`${hash(apk)}  ${manifest.apk}\n${hash(metadata)}  release-manifest.json\n`)]]) };
}
function server({ published = false, wrongSource = false, corrupt = false, existingDraft = false, corruptUpload = false } = {}) {
  const bundle = fixture();
  const calls = [];
  let ref = published || existingDraft ? { object: { type: 'commit', sha: wrongSource ? 'c'.repeat(40) : context.source } } : null;
  let release = published || existingDraft ? { id: 7, tag_name: context.tag, prerelease: true, draft: !published,
    upload_url: 'https://uploads.github.com/release/7/assets{?name,label}', assets: [], html_url: 'https://github.com/preview' } : null;
  const contents = new Map();
  if (release) for (const [name, bytes] of bundle.assets) {
    const url = `https://api.github.com/assets/${release.assets.length + 1}`;
    release.assets.push({ id: release.assets.length + 1, name, url, size: bytes.length });
    contents.set(url, corrupt && name.endsWith('.apk') ? Buffer.from('corrupted') : bytes);
  }
  const request = async (path, options = {}) => {
    calls.push({ path, ...options });
    if (options.binary) return contents.get(path);
    if (path.startsWith('/git/ref/')) return ref;
    if (path === '/git/refs') return ref = { object: { type: 'commit', sha: options.body.sha } };
    if (path.startsWith('/releases/tags/')) return release?.draft ? null : release;
    if (path.startsWith('/releases?')) return release ? [release] : [];
    if (path === '/releases' && options.method === 'POST') return release = {
      ...options.body, id: 7, assets: [], upload_url: 'https://uploads.github.com/release/7/assets{?name,label}', html_url: 'https://github.com/preview',
    };
    if (path.startsWith('https://uploads.github.com/')) {
      const name = new URL(path).searchParams.get('name');
      const url = `https://api.github.com/assets/${calls.length}`;
      const asset = { id: calls.length, name, url, size: options.raw.length };
      contents.set(url, corruptUpload && name.endsWith('.apk') ? Buffer.from('corrupted upload') : options.raw);
      release.assets.push(asset);
      return asset;
    }
    if (options.method === 'DELETE') {
      release.assets = release.assets.filter(asset => asset.id !== Number(path.split('/').at(-1)));
      return null;
    }
    if (path === '/releases/7') {
      if (options.method === 'PATCH') Object.assign(release, options.body);
      return release;
    }
    assert.fail(`Unexpected request ${path}`);
  };
  return { request, calls, bundle, get release() { return release; } };
}

test('preview writes require dev push identity and bounded Android run code', () => {
  for (const override of [{ GITHUB_EVENT_NAME: 'pull_request' }, { GITHUB_REF: 'refs/heads/master' },
    { GITHUB_RUN_NUMBER: '0' }, { GITHUB_RUN_NUMBER: '2100000001' }, { GITHUB_SHA: 'not-a-sha' }, { GITHUB_RUN_ID: '' }]) {
    assert.throws(() => previewContext({ ...env, ...override }));
  }
  assert.equal(context.tag, 'dev-53');
});

test('preview bundle binds package, source, run, APK bytes, and checksum file', () => {
  const directory = mkdtempSync(join(tmpdir(), 'narrio-preview-'));
  const bundle = fixture();
  try {
    for (const [name, bytes] of bundle.assets) writeFileSync(join(directory, name), bytes);
    assert.equal(loadPreview(directory, context).manifest.versionCode, 53);
    for (const override of [{ applicationId: 'app.narrio' }, { versionCode: 52 }, { sourceCommit: 'c'.repeat(40) },
      { workflowRun: 'another-run' }, { version: '1.4.2-dev.52' }]) {
      writeFileSync(join(directory, 'release-manifest.json'), JSON.stringify({ ...bundle.manifest, ...override }));
      assert.throws(() => loadPreview(directory, context));
    }
    writeFileSync(join(directory, 'release-manifest.json'), bundle.assets.get('release-manifest.json'));
    writeFileSync(join(directory, bundle.manifest.apk), 'wrong bytes');
    assert.throws(() => loadPreview(directory, context), /checksum mismatch/);
    writeFileSync(join(directory, bundle.manifest.apk), bundle.assets.get(bundle.manifest.apk));
    writeFileSync(join(directory, 'SHA256SUMS'), 'wrong checksums');
    assert.throws(() => loadPreview(directory, context), /checksum file mismatch/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('fresh publication verifies every upload before publishing a prerelease, never latest stable', async () => {
  const state = server();
  await publishPreview({ ...state, context });
  assert.equal(state.release.draft, false);
  assert.equal(state.release.prerelease, true);
  assert.equal(state.release.make_latest, 'false');
  assert.equal(state.calls.filter(call => call.binary).length, 3);
  const publication = state.calls.findIndex(call => call.method === 'PATCH');
  assert.ok(state.calls.slice(0, publication).filter(call => call.binary).length === 3);
  assert.match(state.release.body, /CI: Pending/);
  assert.match(state.release.body, /releases\/download\/dev-53\/Narrio-1.4.2-dev.53.apk/);
  assert.equal(state.calls[1].body.sha, context.source);
});

test('published retries verify bytes without changing assets, tags, or completed status', async () => {
  const state = server({ published: true });
  state.release.body = 'CI: Passed';
  await publishPreview({ ...state, context });
  assert.equal(state.release.body, 'CI: Passed');
  assert.ok(state.calls.every(call => !['POST', 'PATCH', 'DELETE'].includes(call.method)));
});

test('a corrupted fresh upload cannot be published', async () => {
  const state = server({ corruptUpload: true });
  await assert.rejects(publishPreview({ ...state, context }), /Uploaded preview differs/);
  assert.equal(state.release.draft, true);
  assert.ok(!state.calls.some(call => call.method === 'PATCH'));
});

test('mismatched published bytes or source cannot be overwritten', async () => {
  for (const options of [{ published: true, corrupt: true }, { published: true, wrongSource: true }]) {
    const state = server(options);
    await assert.rejects(publishPreview({ ...state, context }), /differs|another source/);
    assert.ok(state.calls.every(call => !['POST', 'PATCH', 'DELETE'].includes(call.method)));
  }
});

test('an interrupted draft upload is repaired and verified before publication', async () => {
  const state = server({ existingDraft: true, corrupt: true });
  await publishPreview({ ...state, context });
  assert.equal(state.calls.filter(call => call.method === 'DELETE').length, 1);
  assert.equal(state.release.draft, false);
});

test('final status distinguishes successful, failed, and cancelled checks', async () => {
  for (const [results, label] of [[['success', 'success'], 'Passed'], [['success', 'failure'], 'Failed'],
    [['success', 'cancelled'], 'Incomplete'], [['skipped', 'success'], 'Incomplete']]) {
    const state = server({ published: true });
    await updatePreviewStatus({ ...state, context, status: ciStatus(results) });
    assert.match(state.release.body, new RegExp(`CI: ${label}`));
    assert.ok(state.calls.filter(call => call.method).every(call => call.method === 'PATCH'));
  }
  assert.throws(() => previewBody(fixture(), context, 'unknown'));
});

test('status updates cannot attach a different source or artifact manifest to a preview', async () => {
  for (const wrongSource of [true, false]) {
    const state = server({ published: true, wrongSource });
    if (!wrongSource) state.bundle.assets.set('release-manifest.json', Buffer.from('another build'));
    await assert.rejects(updatePreviewStatus({ ...state, context, status: 'passed' }), /another source|differs/);
    assert.ok(!state.calls.some(call => call.method === 'PATCH'));
  }
});
