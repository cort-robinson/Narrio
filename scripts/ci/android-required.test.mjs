import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { androidRequired, classify } from './android-required.mjs';

const pr = paths => ({ event: 'pull_request', baseBranch: 'dev', paths });

test('only known prose-only dev PRs skip Android', () => {
  assert.equal(androidRequired(pr(['README.md', 'AGENTS.md', 'docs/DEVELOPMENT.md'])), false);
  for (const path of ['app/src/main/App.kt', '.github/workflows/ci.yml', '.github/android-smoke-tests.txt', 'gradle.properties',
    'package.json', 'scripts/release/preview.mjs', 'docs/fixture.apk', 'app/README.md']) {
    assert.equal(androidRequired(pr(['README.md', path])), true, path);
  }
  assert.equal(androidRequired(pr([])), true);
  assert.equal(androidRequired(pr(undefined)), true);
  assert.equal(androidRequired({ ...pr(['README.md']), baseBranch: 'master' }), true);
  for (const event of ['push', 'workflow_dispatch']) {
    assert.equal(androidRequired({ ...pr(['README.md']), event }), true);
  }
});

test('git comparison includes deleted code paths when renamed into documentation', () => {
  const directory = mkdtempSync(join(tmpdir(), 'narrio-ci-'));
  const original = process.cwd();
  const git = (...args) => execFileSync('git', args, { cwd: directory, encoding: 'utf8' }).trim();
  try {
    git('init', '--quiet');
    git('config', 'user.name', 'CI test');
    git('config', 'user.email', 'ci@example.invalid');
    writeFileSync(join(directory, 'code.kt'), 'example code\n');
    git('add', '.');
    git('commit', '--quiet', '-m', 'test: baseline');
    const base = git('rev-parse', 'HEAD');
    mkdirSync(join(directory, 'docs'));
    git('mv', 'code.kt', 'docs/moved.md');
    git('commit', '--quiet', '-m', 'test: move code');
    process.chdir(directory);
    const env = { GITHUB_EVENT_NAME: 'pull_request', PR_BASE_BRANCH: 'dev',
      PR_BASE_SHA: base, PR_HEAD_SHA: git('rev-parse', 'HEAD') };
    assert.equal(classify(env), true);
    assert.equal(classify({ ...env, PR_BASE_SHA: 'missing-commit' }), true);
    git('reset', '--hard', base);
    writeFileSync(join(directory, 'README.md'), 'documentation\n');
    git('add', '.');
    git('commit', '--quiet', '-m', 'docs: readme');
    assert.equal(classify({ ...env, PR_HEAD_SHA: git('rev-parse', 'HEAD') }), false);
  } finally {
    process.chdir(original);
    rmSync(directory, { recursive: true, force: true });
  }
});
