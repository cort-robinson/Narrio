import { execFileSync } from 'node:child_process';
import { analyzeCommits } from '@semantic-release/commit-analyzer';
import { generateNotes } from '@semantic-release/release-notes-generator';
import { commitOptions, notesOptions } from './options.mjs';

export const quietLogger = { log() {}, warn() {}, error() {} };
export const git = (args, cwd = process.cwd()) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();

export function versionCode(version) {
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) throw new Error(`Invalid stable version: ${version}`);
  const [major, minor, patch] = version.split('.').map(Number);
  const code = major * 1_000_000 + minor * 1_000 + patch;
  if (minor >= 1000 || patch >= 1000 || !Number.isSafeInteger(code) || code <= 0 || code > 2_100_000_000) {
    throw new Error(`Version cannot be represented by an increasing Android versionCode: ${version}`);
  }
  return code;
}

export function stableTags(cwd = process.cwd()) {
  return git(['tag', '--merged', 'HEAD', '--list', 'v[0-9]*'], cwd).split('\n')
    .filter(tag => /^v\d+\.\d+\.\d+$/.test(tag))
    .sort((a, b) => versionCode(b.slice(1)) - versionCode(a.slice(1)));
}

export function commitsSince(tag, cwd = process.cwd()) {
  const fields = git(['log', `${tag}..HEAD`, '--format=%H%x00%B%x00'], cwd).split('\0');
  const commits = [];
  for (let i = 0; i + 1 < fields.length; i += 2) {
    commits.push({ hash: fields[i].trim(), message: fields[i + 1].trim() });
  }
  return commits;
}

export function increment(version, type) {
  const [major, minor, patch] = version.split('.').map(Number);
  const next = type === 'major' ? `${major + 1}.0.0` : type === 'minor' ? `${major}.${minor + 1}.0` : `${major}.${minor}.${patch + 1}`;
  versionCode(next);
  return next;
}

export async function notesFor(version, previousTag, cwd = process.cwd()) {
  return generateNotes(notesOptions, {
    cwd, logger: quietLogger,
    options: { repositoryUrl: git(['remote', 'get-url', 'origin'], cwd) },
    commits: commitsSince(previousTag, cwd),
    lastRelease: { version: previousTag.slice(1), gitTag: previousTag },
    nextRelease: { version, gitTag: `v${version}`, gitHead: git(['rev-parse', 'HEAD'], cwd) },
  });
}

export async function planRelease(cwd = process.cwd()) {
  const lastTag = stableTags(cwd)[0];
  if (!lastTag) throw new Error('A stable baseline tag is required before planning a release');
  const commits = commitsSince(lastTag, cwd);
  const type = await analyzeCommits(commitOptions, { cwd, commits, logger: quietLogger });
  const version = type ? increment(lastTag.slice(1), type) : null;
  return { lastTag, type: type ?? null, version, notes: version ? await notesFor(version, lastTag, cwd) : '' };
}
