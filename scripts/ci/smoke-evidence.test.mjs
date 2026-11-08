import assert from 'node:assert/strict';
import test from 'node:test';
import { evidenceName, reuseAllowed, trustedEvidence } from './smoke-evidence.mjs';

const REPO = 42;
const WORKFLOW = '.github/workflows/ci.yml';
const artifact = (id, overrides = {}) => ({
  expired: false, workflow_run: { id, repository_id: REPO, head_repository_id: REPO }, ...overrides,
});
const run = (id, overrides = {}) => [id, {
  path: WORKFLOW, html_url: `https://example.test/runs/${id}`,
  repository: { id: REPO }, head_repository: { id: REPO }, ...overrides,
}];
const find = (artifacts, runs) =>
  trustedEvidence({ artifacts, runs: new Map(runs), repositoryId: REPO, workflowPath: WORKFLOW });

test('the evidence name holds the tested tree', () => {
  assert.equal(evidenceName('abc123'), 'android-smoke-passed-abc123');
});

test("a passing run of this repository's own workflow is reused", () => {
  assert.deepEqual(find([artifact(7)], [run(7)]), { runId: 7, url: 'https://example.test/runs/7' });
});

test('forks, other workflows, expired results, and unknown runs are not trusted', () => {
  const untrusted = [
    [[artifact(1, { expired: true })], [run(1)]],
    [[artifact(2, { workflow_run: { id: 2, repository_id: REPO, head_repository_id: 99 } })], [run(2)]],
    [[artifact(3)], [run(3, { head_repository: { id: 99 } })]],
    [[artifact(4)], [run(4, { path: '.github/workflows/other.yml' })]],
    [[artifact(5)], [run(5, { repository: { id: 99 } })]],
    [[artifact(6)], []],
    [[artifact(7, { workflow_run: null })], [run(7)]],
    [[], []],
  ];
  for (const [artifacts, runs] of untrusted) assert.equal(find(artifacts, runs), null, JSON.stringify(artifacts));
  // An untrusted candidate does not hide a trusted one.
  assert.equal(find([artifact(2, { expired: true }), artifact(8)], [run(2), run(8)]).runId, 8);
});

test('manual runs and runs without Android work never reuse results', () => {
  assert.equal(reuseAllowed({ event: 'push', androidRequired: true }), true);
  assert.equal(reuseAllowed({ event: 'pull_request', androidRequired: true }), true);
  assert.equal(reuseAllowed({ event: 'workflow_dispatch', androidRequired: true }), false);
  assert.equal(reuseAllowed({ event: 'pull_request', androidRequired: false }), false);
});
