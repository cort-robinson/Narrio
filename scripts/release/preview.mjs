import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { planRelease } from './plan.mjs';
import { prepareApk } from './android.mjs';

const plan = await planRelease();
const summary = plan.version
  ? `## Proposed release: v${plan.version}\n\n${plan.notes}`
  : `## No new stable release\n\nChanges since ${plan.lastTag} do not require a version increment.\n`;
mkdirSync('artifacts', { recursive: true });
writeFileSync('artifacts/release-preview.md', summary);
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
console.log(summary);

if (process.argv.includes('--build')) {
  const run = Number(process.env.GITHUB_RUN_NUMBER);
  if (!Number.isSafeInteger(run) || run <= 0) throw new Error('GITHUB_RUN_NUMBER must be a positive integer for a preview APK');
  prepareApk(`${plan.version ?? plan.lastTag.slice(1)}-dev.${run}`, { preview: true, code: run });
}
