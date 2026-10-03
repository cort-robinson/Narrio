import { createHash } from 'node:crypto';
import { readFileSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { git } from './plan.mjs';
import { verifyManifest } from './android.mjs';
import { findRelease } from './publish.mjs';

const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function previewContext(env) {
  if (env.GITHUB_EVENT_NAME !== 'push' || env.GITHUB_REF !== 'refs/heads/dev') {
    throw new Error('Preview publication requires a trusted dev push');
  }
  const run = Number(env.GITHUB_RUN_NUMBER);
  if (!Number.isSafeInteger(run) || run <= 0 || run > 2_100_000_000 ||
      !/^\d+$/.test(env.GITHUB_RUN_ID ?? '') || !/^[a-f0-9]{40}$/.test(env.GITHUB_SHA ?? '') ||
      !/^[\w.-]+\/[\w.-]+$/.test(env.GITHUB_REPOSITORY ?? '')) {
    throw new Error('Invalid preview workflow identity');
  }
  const repositoryUrl = `${env.GITHUB_SERVER_URL || 'https://github.com'}/${env.GITHUB_REPOSITORY}`;
  return { run, tag: `dev-${run}`, source: env.GITHUB_SHA, repositoryUrl,
    runUrl: `${repositoryUrl}/actions/runs/${env.GITHUB_RUN_ID}` };
}

export function loadPreview(directory, context) {
  const manifestBytes = readFileSync(join(directory, 'release-manifest.json'));
  const manifest = JSON.parse(manifestBytes);
  if (!/^\d+\.\d+\.\d+-dev\.\d+$/.test(manifest.version) ||
      !manifest.version.endsWith(`-dev.${context.run}`) || manifest.versionCode !== context.run ||
      manifest.workflowRun !== context.runUrl) throw new Error('Preview does not belong to this workflow run');
  verifyManifest(manifest, { version: manifest.version, sourceCommit: context.source, preview: true });
  const apk = readFileSync(join(directory, manifest.apk));
  if (apk.length !== manifest.bytes || digest(apk) !== manifest.sha256) throw new Error('Preview APK checksum mismatch');
  const checksums = readFileSync(join(directory, 'SHA256SUMS'));
  const expected = `${manifest.sha256}  ${manifest.apk}\n${digest(manifestBytes)}  release-manifest.json\n`;
  if (checksums.toString() !== expected) throw new Error('Preview checksum file mismatch');
  return { manifest, assets: new Map([[manifest.apk, apk], ['release-manifest.json', manifestBytes], ['SHA256SUMS', checksums]]) };
}

export function ciStatus(results) {
  if (results.every(result => result === 'success')) return 'passed';
  if (results.includes('failure')) return 'failed';
  return 'cancelled';
}

export function previewBody(bundle, context, status) {
  const labels = { pending: 'Pending — tests are still running. If this run is superseded or cancelled, check the linked workflow.',
    passed: 'Passed — build, unit tests, lint, release-tooling tests, and Android emulator smoke tests.',
    failed: 'Failed — check the workflow before relying on this preview.',
    cancelled: 'Incomplete — checks were cancelled or skipped. See the workflow for details.' };
  if (!Object.hasOwn(labels, status)) throw new Error('Invalid preview CI status');
  const download = `${context.repositoryUrl}/releases/download/${context.tag}/${bundle.manifest.apk}`;
  return `**[Download Narrio Dev APK](${download})**\n\n` +
    `Open the APK on your Android phone and approve installation. It installs alongside stable Narrio; updates retain Narrio Dev data.\n\n` +
    `**CI: ${labels[status]}**\n\n[Workflow and test results](${context.runUrl}) · ` +
    `[Source ${context.source.slice(0, 7)}](${context.repositoryUrl}/commit/${context.source})\n\n` +
    `Version: \`${bundle.manifest.version}\` · Android code: ${context.run}\n\n` +
    `APK SHA-256: \`${bundle.manifest.sha256}\`\n\n` +
    `These automated checks do not establish physical-phone or live-provider acceptance.\n`;
}

async function ensureSource(request, context, create) {
  const path = `/git/ref/tags/${context.tag}`;
  let ref = await request(path, { allowMissing: true });
  if (!ref && create) {
    // Create the lightweight tag at the triggering workflow's SHA, never at the default branch.
    ref = await request('/git/refs', { method: 'POST', body: { ref: `refs/tags/${context.tag}`, sha: context.source } });
  }
  if (!ref || ref.object.type !== 'commit' || ref.object.sha !== context.source) {
    throw new Error('Preview tag belongs to another source; tags are never moved');
  }
}

async function verifyAsset(request, asset, bytes) {
  const remote = await request(asset.url, { binary: true });
  if (asset.size !== bytes.length || digest(remote) !== digest(bytes)) throw new Error(`Uploaded preview differs: ${asset.name}`);
}

export async function publishPreview({ request, bundle, context }) {
  await ensureSource(request, context, true);
  let release = await findRelease(context.tag, request);
  if (!release) release = await request('/releases', { method: 'POST', body: {
    tag_name: context.tag, target_commitish: context.source, name: `Narrio Dev ${context.run}`,
    prerelease: true, draft: true, make_latest: 'false', body: previewBody(bundle, context, 'pending'),
  } });
  if (release.tag_name !== context.tag || !release.prerelease) throw new Error('Expected a dedicated preview prerelease');
  if (release.assets.some(asset => !bundle.assets.has(asset.name))) throw new Error('Unexpected preview release assets');
  for (const [name, bytes] of bundle.assets) {
    let asset = release.assets.find(item => item.name === name);
    if (asset) {
      try { await verifyAsset(request, asset, bytes); }
      catch (error) {
        if (!release.draft) throw error; // Published bytes are immutable, including on retries.
        await request(`/releases/assets/${asset.id}`, { method: 'DELETE' });
        asset = null;
      }
    }
    if (!asset) {
      if (!release.draft) throw new Error(`Published preview is missing ${name}`);
      const url = `${release.upload_url.split('{')[0]}?name=${encodeURIComponent(name)}`;
      asset = await request(url, { method: 'POST', raw: bytes, contentType: name.endsWith('.apk') ? 'application/vnd.android.package-archive' : 'application/octet-stream' });
      await verifyAsset(request, asset, bytes);
    }
  }
  if (release.draft) release = await request(`/releases/${release.id}`, { method: 'PATCH', body: {
    draft: false, prerelease: true, make_latest: 'false', body: previewBody(bundle, context, 'pending'),
  } });
  return release;
}

export async function updatePreviewStatus({ request, bundle, context, status }) {
  await ensureSource(request, context, false);
  const release = await findRelease(context.tag, request);
  if (!release || release.draft || !release.prerelease) throw new Error('Published preview not found');
  const manifestAsset = release.assets.find(asset => asset.name === 'release-manifest.json');
  if (!manifestAsset) throw new Error('Preview manifest missing');
  await verifyAsset(request, manifestAsset, bundle.assets.get('release-manifest.json'));
  return request(`/releases/${release.id}`, { method: 'PATCH', body: { body: previewBody(bundle, context, status), make_latest: 'false' } });
}

function githubRequest(env) {
  if (!env.GITHUB_TOKEN) throw new Error('GitHub token required');
  const base = `${env.GITHUB_API_URL || 'https://api.github.com'}/repos/${env.GITHUB_REPOSITORY}`;
  return async (path, options = {}) => {
    const headers = { Authorization: `Bearer ${env.GITHUB_TOKEN}`, Accept: options.binary ? 'application/octet-stream' : 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': options.contentType || 'application/json' };
    const response = await fetch(path.startsWith('https://') ? path : `${base}${path}`, {
      method: options.method || 'GET', headers, body: options.raw ?? (options.body ? JSON.stringify(options.body) : undefined),
    });
    if (response.status === 404 && options.allowMissing) return null;
    if (!response.ok) throw new Error(`Preview GitHub request failed: ${response.status} ${path}`);
    if (options.binary) return Buffer.from(await response.arrayBuffer());
    return response.status === 204 ? null : response.json();
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const context = previewContext(process.env);
  if (git(['rev-parse', 'HEAD']) !== context.source) throw new Error('Checkout does not match preview source');
  const bundle = loadPreview('artifacts', context);
  const request = githubRequest(process.env);
  const release = process.argv.includes('--status')
    ? await updatePreviewStatus({ request, bundle, context, status: ciStatus([process.env.BUILD_RESULT, process.env.NATIVE_RESULT]) })
    : await publishPreview({ request, bundle, context });
  console.log(`Narrio Dev: ${release.html_url}`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${previewBody(bundle, context, process.argv.includes('--status') ? ciStatus([process.env.BUILD_RESULT, process.env.NATIVE_RESULT]) : 'pending')}\n`);
}
