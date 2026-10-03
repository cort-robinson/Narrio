import semanticRelease from 'semantic-release';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { git, notesFor, stableTags } from './plan.mjs';
import { baselineVersion } from './options.mjs';
import { prepareApk, verifyManifest } from './android.mjs';

export function recoveryAction({ version, baseline, published, tagCommit, head }) {
  if (published || version === baseline) return 'continue';
  if (tagCommit !== head) throw new Error(`Unpublished v${version} belongs to ${tagCommit}. Re-run that release workflow before releasing newer commits.`);
  return 'recover';
}

const repository = process.env.GITHUB_REPOSITORY;
const token = process.env.GITHUB_TOKEN;
const api = process.env.GITHUB_API_URL || 'https://api.github.com';

async function request(path, options = {}) {
  const response = await fetch(`${api}/repos/${repository}${path}`, {
    ...options,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json', ...options.headers },
  });
  if (response.status === 404 && options.allowMissing) return null;
  if (!response.ok) throw new Error(`GitHub release request failed: ${response.status} ${path}`);
  return response.status === 204 ? null : response.json();
}

export async function findRelease(tag, apiRequest = request) {
  const published = await apiRequest(`/releases/tags/${encodeURIComponent(tag)}`, { allowMissing: true });
  if (published) return published;
  // GitHub's tag endpoint does not return drafts. Retry lookup must include them.
  for (let page = 1; ; page++) {
    const releases = await apiRequest(`/releases?per_page=100&page=${page}`);
    const draft = releases.find(release => release.tag_name === tag);
    if (draft) return apiRequest(`/releases/${draft.id}`);
    if (releases.length < 100) return null;
  }
}

export async function finalize(tag, releaseId) {
  const release = releaseId ? await request(`/releases/${releaseId}`) : await findRelease(tag);
  if (!release || release.tag_name !== tag) throw new Error(`Cannot locate release for ${tag}`);
  if (!release.draft) { console.log(`${tag} is already published`); return; }
  const localManifest = JSON.parse(readFileSync('artifacts/release-manifest.json', 'utf8'));
  const sourceCommit = git(['rev-parse', `${tag}^{commit}`]);
  verifyManifest(localManifest, { version: tag.slice(1), sourceCommit });
  const expectedAssets = [localManifest.apk, 'release-manifest.json', 'SHA256SUMS'];
  if (release.assets.length !== expectedAssets.length) throw new Error('Draft release has unexpected or missing assets');
  for (const name of expectedAssets) {
    const asset = release.assets.find(item => item.name === name);
    if (!asset) throw new Error(`Missing draft release asset: ${name}`);
    const bytes = readFileSync(`artifacts/${name}`);
    const response = await fetch(asset.url, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/octet-stream', 'X-GitHub-Api-Version': '2022-11-28' },
    });
    if (!response.ok) throw new Error(`Cannot verify uploaded asset: ${name}`);
    const remote = Buffer.from(await response.arrayBuffer());
    if (asset.size !== bytes.length || createHash('sha256').update(remote).digest('hex') !== createHash('sha256').update(bytes).digest('hex')) {
      throw new Error(`Uploaded release asset differs from the verified build: ${name}`);
    }
  }
  const updated = await request(`/releases/${release.id}`, { method: 'PATCH', body: JSON.stringify({ draft: false, make_latest: 'true' }) });
  console.log(`Published ${updated.html_url}`);
}

async function publish() {
  if (!repository || !token || process.env.GITHUB_REF !== 'refs/heads/master' || process.env.GITHUB_EVENT_NAME !== 'push') {
    throw new Error('Stable publication is allowed only in a trusted master push workflow');
  }
  const tags = stableTags();
  if (tags[0]) {
    const tag = tags[0];
    const release = await findRelease(tag);
    const action = recoveryAction({
      version: tag.slice(1), baseline: baselineVersion,
      published: Boolean(release && !release.draft),
      tagCommit: git(['rev-parse', `${tag}^{commit}`]), head: git(['rev-parse', 'HEAD']),
    });
    if (action === 'recover') {
      prepareApk(tag.slice(1));
      const notes = await notesFor(tag.slice(1), tags[1]);
      mkdirSync('artifacts', { recursive: true });
      writeFileSync('artifacts/release-notes.md', notes);
      const assets = [`artifacts/Narrio-${tag.slice(1)}.apk`, 'artifacts/release-manifest.json', 'artifacts/SHA256SUMS'];
      if (!release) {
        execFileSync('gh', ['release', 'create', tag, '--repo', repository, '--verify-tag', '--draft', '--title', tag, '--notes-file', 'artifacts/release-notes.md', ...assets], { stdio: 'inherit' });
      } else {
        execFileSync('gh', ['release', 'upload', tag, '--repo', repository, '--clobber', ...assets], { stdio: 'inherit' });
      }
      await finalize(tag, release?.id);
      return;
    }
  }
  const result = await semanticRelease();
  if (result) await finalize(result.nextRelease.gitTag, result.releases.find(release => release.pluginName === '@semantic-release/github')?.id);
  else console.log('No stable version increment is needed');
}

// Tests import the recovery policy without running publication.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await publish();
