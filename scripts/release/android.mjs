import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { git, versionCode } from './plan.mjs';

function run(command, args, options = {}) {
  if (process.platform === 'win32' && command.endsWith('.bat')) {
    return execFileSync('cmd.exe', ['/d', '/c', `.\\${basename(command)}`, ...args], { ...options, cwd: dirname(command) });
  }
  return execFileSync(command, args, options);
}

export function requireSigning() {
  for (const name of ['NARRIO_KEYSTORE_PATH', 'NARRIO_KEYSTORE_PASSWORD', 'NARRIO_KEY_ALIAS', 'NARRIO_KEY_PASSWORD', 'NARRIO_EXPECTED_CERT_SHA256']) {
    if (!process.env[name]) throw new Error(`Missing signing configuration: ${name}`);
  }
}

export function certificateDigest(signature) {
  const digests = new Set([...signature.matchAll(/certificate SHA-256 digest:\s*([a-f0-9]{64})\b/gi)].map(match => match[1].toLowerCase()));
  if (digests.size !== 1) throw new Error(`Expected one APK signing identity; apksigner returned:\n${signature}`);
  return [...digests][0];
}

export function verifyManifest(manifest, { version, sourceCommit, preview = false }) {
  if (manifest.version !== version || manifest.sourceCommit !== sourceCommit || manifest.applicationId !== (preview ? 'app.narrio.dev' : 'app.narrio')) {
    throw new Error('Release manifest does not match the requested version, source, and application');
  }
  if (!preview && manifest.versionCode !== versionCode(version)) throw new Error('Release versionCode does not match its semantic version');
  if (!/^[a-f0-9]{64}$/.test(manifest.sha256) || !/^[a-f0-9]{64}$/.test(manifest.certificateSha256)) throw new Error('Invalid APK or certificate digest');
  if (manifest.apk !== `Narrio-${version}.apk` || !Number.isSafeInteger(manifest.bytes) || manifest.bytes <= 0) throw new Error('Invalid APK asset details');
}

export function prepareApk(version, { preview = false, code = versionCode(version), cwd = process.cwd() } = {}) {
  requireSigning();
  if (!/^\d+\.\d+\.\d+(?:-dev\.\d+)?$/.test(version) || !Number.isSafeInteger(code) || code <= 0 || code > 2_100_000_000) throw new Error('Invalid APK version');
  const args = ['--no-daemon', ':app:assembleRelease', '-PrequireSigning=true', `-PappVersionName=${version}`, `-PappVersionCode=${code}`];
  if (preview) args.push('-PnarrioPreview=true');
  run(join(cwd, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew'), args, { cwd, stdio: 'inherit' });

  let sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk && existsSync(join(cwd, 'local.properties'))) {
    sdk = readFileSync(join(cwd, 'local.properties'), 'utf8').match(/^sdk\.dir=(.*)$/m)?.[1].trim().replace(/\\:/g, ':').replace(/\\\\/g, '\\');
  }
  if (!sdk) throw new Error('ANDROID_HOME or ANDROID_SDK_ROOT is required');
  const toolVersion = readdirSync(join(sdk, 'build-tools')).filter(value => /^\d+\.\d+\.\d+$/.test(value))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))[0];
  if (!toolVersion) throw new Error('Android build tools are missing');
  const toolDirectory = join(sdk, 'build-tools', toolVersion);
  const built = join(cwd, 'app/build/outputs/apk/release/app-release.apk');
  const signature = run(join(toolDirectory, process.platform === 'win32' ? 'apksigner.bat' : 'apksigner'), ['verify', '--verbose', '--print-certs', built], { encoding: 'utf8' });
  const certificate = certificateDigest(signature);
  const expectedCertificate = process.env.NARRIO_EXPECTED_CERT_SHA256.trim().toLowerCase();
  if (certificate !== expectedCertificate) throw new Error(`APK signing certificate mismatch: expected ${expectedCertificate}, built ${certificate}`);
  const badging = run(join(toolDirectory, process.platform === 'win32' ? 'aapt2.exe' : 'aapt2'), ['dump', 'badging', built], { encoding: 'utf8' });
  const applicationId = preview ? 'app.narrio.dev' : 'app.narrio';
  const expectedPackage = `package: name='${applicationId}' versionCode='${code}' versionName='${version}'`;
  if (!badging.includes(expectedPackage)) throw new Error('Built APK has unexpected applicationId, versionName, or versionCode');
  if (preview && !badging.includes("application-label:'Narrio Dev'")) throw new Error('Preview APK must be named Narrio Dev');

  const directory = resolve(cwd, 'artifacts');
  mkdirSync(directory, { recursive: true });
  const apk = `Narrio-${version}.apk`;
  copyFileSync(built, join(directory, apk));
  const bytes = readFileSync(join(directory, apk));
  const manifest = {
    version, versionCode: code, applicationId, apk,
    sourceCommit: git(['rev-parse', 'HEAD'], cwd),
    sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length,
    certificateSha256: certificate,
    workflowRun: process.env.GITHUB_RUN_ID ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}` : null,
  };
  verifyManifest(manifest, { version, sourceCommit: manifest.sourceCommit, preview });
  const manifestBytes = `${JSON.stringify(manifest, null, 2)}\n`;
  writeFileSync(join(directory, 'release-manifest.json'), manifestBytes);
  writeFileSync(join(directory, 'SHA256SUMS'), `${manifest.sha256}  ${apk}\n${createHash('sha256').update(manifestBytes).digest('hex')}  release-manifest.json\n`);
  return manifest;
}
