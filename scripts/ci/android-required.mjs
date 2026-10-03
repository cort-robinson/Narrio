import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// An allowlist: unknown paths, empty diffs, and uncertain comparisons run Android.
export function androidRequired({ event, baseBranch, paths }) {
  if (event !== 'pull_request' || baseBranch !== 'dev' || !paths?.length) return true;
  return paths.some(path => !(
    ['README.md', 'AGENTS.md'].includes(path) || /^docs\/.*\.md$/.test(path)
  ));
}

export function classify(env = process.env) {
  let paths;
  if (env.GITHUB_EVENT_NAME === 'pull_request' && env.PR_BASE_BRANCH === 'dev') {
    try {
      // Disable rename detection so a code file moved into docs still runs Android.
      paths = execFileSync('git', [
        'diff', '--no-renames', '--name-only', '-z',
        `${env.PR_BASE_SHA}...${env.PR_HEAD_SHA}`, '--',
      ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).split('\0').filter(Boolean);
    } catch {
      console.warn('Could not establish changed paths; running all Android checks.');
    }
  }
  return androidRequired({ event: env.GITHUB_EVENT_NAME, baseBranch: env.PR_BASE_BRANCH, paths });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const required = classify();
  appendFileSync(process.env.GITHUB_OUTPUT, `android=${required}\n`);
  console.log(required ? 'Run all Android checks.' : 'Documentation-only dev PR: Android checks are unnecessary.');
}
