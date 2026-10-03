import { prepareApk, requireSigning } from './android.mjs';

export function verifyConditions() { requireSigning(); }
export function prepare(_options, { nextRelease, cwd }) { prepareApk(nextRelease.version, { cwd }); }
