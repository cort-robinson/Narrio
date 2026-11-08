import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// A run that passes every smoke-test shard uploads an artifact with this name. The name holds
// the Git tree it tested: the files themselves, including this workflow and the tests.
export const evidenceName = tree => `android-smoke-passed-${tree}`;

// Picks a passing run's artifact that can stand in for running the emulator suite again.
// Only this repository's own runs of this same workflow count, never a fork's.
export function trustedEvidence({ artifacts, runs, repositoryId, workflowPath }) {
  for (const artifact of artifacts) {
    const run = artifact.workflow_run;
    if (artifact.expired || !run) continue;
    if (run.repository_id !== repositoryId || run.head_repository_id !== repositoryId) continue;
    const details = runs.get(run.id);
    if (details?.path !== workflowPath || details.repository?.id !== repositoryId) continue;
    if (details.head_repository?.id !== repositoryId) continue;
    return { runId: run.id, url: details.html_url };
  }
  return null;
}

// Manual runs always test, so there is a way to rerun the emulators on a tree that passed before.
export function reuseAllowed({ event, androidRequired }) {
  return androidRequired && event !== 'workflow_dispatch';
}

async function api(path, token) {
  const response = await fetch(`${process.env.GITHUB_API_URL ?? 'https://api.github.com'}${path}`, {
    headers: { authorization: `Bearer ${token}`, accept: 'application/vnd.github+json', 'x-github-api-version': '2022-11-28' },
  });
  if (!response.ok) throw new Error(`GET ${path} returned ${response.status}`);
  return response.json();
}

export async function findEvidence(tree, env = process.env) {
  const repo = env.GITHUB_REPOSITORY;
  const repositoryId = JSON.parse(readFileSync(env.GITHUB_EVENT_PATH, 'utf8')).repository.id;
  // GITHUB_WORKFLOW_REF looks like owner/repo/.github/workflows/ci.yml@refs/heads/dev.
  const workflowPath = env.GITHUB_WORKFLOW_REF.slice(repo.length + 1).replace(/@.*$/, '');
  const { artifacts } = await api(`/repos/${repo}/actions/artifacts?per_page=100&name=${evidenceName(tree)}`, env.GITHUB_TOKEN);
  const runs = new Map();
  for (const id of new Set(artifacts.map(artifact => artifact.workflow_run?.id).filter(Boolean))) {
    runs.set(id, await api(`/repos/${repo}/actions/runs/${id}`, env.GITHUB_TOKEN));
  }
  return trustedEvidence({ artifacts, runs, repositoryId, workflowPath });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const androidRequired = process.env.ANDROID_REQUIRED === 'true';
  let tree = '';
  let evidence = null;
  try {
    tree = execFileSync('git', ['rev-parse', 'HEAD^{tree}'], { encoding: 'utf8' }).trim();
    if (reuseAllowed({ event: process.env.GITHUB_EVENT_NAME, androidRequired })) {
      evidence = await findEvidence(tree);
    }
  } catch (error) {
    // Any doubt runs the emulators.
    console.warn(`::warning::Could not look up earlier smoke-test results; running them. ${error.message}`);
    evidence = null;
  }
  appendFileSync(process.env.GITHUB_OUTPUT, `tree=${tree}\nsmoke_reused=${Boolean(evidence)}\n`);
  if (evidence) {
    const message = `Android smoke tests already passed on this exact source tree (${tree}) in ${evidence.url}; not running them again.`;
    console.log(message);
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${message}\n`);
  } else if (androidRequired) {
    console.log(`No earlier passing smoke-test run for tree ${tree}; running them.`);
  }
}
