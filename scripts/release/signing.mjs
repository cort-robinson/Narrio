import { appendFileSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

if (!process.env.RUNNER_TEMP) throw new Error('RUNNER_TEMP is required');
const directory = join(resolve(process.env.RUNNER_TEMP), 'narrio-signing');
if (process.argv[2] === 'clean') {
  rmSync(directory, { recursive: true, force: true });
} else {
  if (!process.env.GITHUB_ENV || !process.env.SIGNING_KEYSTORE_BASE64) throw new Error('Signing secret and GITHUB_ENV are required');
  const key = Buffer.from(process.env.SIGNING_KEYSTORE_BASE64, 'base64');
  if (key.length < 1000) throw new Error('Invalid signing keystore');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const path = join(directory, 'identity.jks');
  writeFileSync(path, key, { mode: 0o600 });
  appendFileSync(process.env.GITHUB_ENV, `NARRIO_KEYSTORE_PATH=${path}\n`);
}
