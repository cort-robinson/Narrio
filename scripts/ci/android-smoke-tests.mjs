import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const SMOKE_TEST_LIST = '.github/android-smoke-tests.txt';
const CLASS_NAME = /^[a-z_][\w]*(\.[a-z_][\w]*)*\.[A-Z][\w$]*$/;

export const DEFAULT_SECONDS = 30;

// One fully qualified class per line, optionally followed by its measured CI seconds.
// Blank lines and # comments are ignored.
export function parseSmokeEntries(text) {
  const entries = [];
  for (const [index, raw] of text.split(/\r?\n/).entries()) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const [name, seconds, ...rest] = line.split(/\s+/);
    if (!CLASS_NAME.test(name)) throw new Error(`Line ${index + 1} is not a fully qualified test class: ${line}`);
    if (rest.length || (seconds !== undefined && !/^[1-9]\d*$/.test(seconds))) {
      throw new Error(`Line ${index + 1} should be a class name and optional whole seconds: ${line}`);
    }
    if (entries.some(entry => entry.name === name)) throw new Error(`Line ${index + 1} repeats ${name}`);
    entries.push({ name, seconds: seconds === undefined ? DEFAULT_SECONDS : Number(seconds) });
  }
  if (!entries.length) throw new Error('The smoke-test list is empty');
  return entries;
}

export function parseSmokeTests(text) {
  return parseSmokeEntries(text).map(entry => entry.name);
}

// Splits whole classes across shards, longest first onto the least-loaded shard. Classes stay
// whole because a class's tests may depend on each other's saved state. Returns class names in list order.
export function shardClasses(entries, shardIndex, shardCount) {
  if (!Number.isInteger(shardCount) || shardCount < 1 || !Number.isInteger(shardIndex)
    || shardIndex < 0 || shardIndex >= shardCount) {
    throw new Error(`Invalid shard ${shardIndex} of ${shardCount}`);
  }
  const loads = Array(shardCount).fill(0);
  const assigned = new Map();
  const longestFirst = entries.map((entry, order) => ({ ...entry, order }))
    .sort((a, b) => b.seconds - a.seconds || a.order - b.order);
  for (const entry of longestFirst) {
    const shard = loads.indexOf(Math.min(...loads));
    loads[shard] += entry.seconds;
    assigned.set(entry.name, shard);
  }
  return entries.filter(entry => assigned.get(entry.name) === shardIndex).map(entry => entry.name);
}

// Counts executed (not skipped) test cases per class in JUnit XML reports.
export function executedTests(xmlReports) {
  const counts = new Map();
  for (const xml of xmlReports) {
    for (const [, attributes, body = ''] of xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const className = attributes.match(/\bclassname="([^"]*)"/)?.[1];
      if (className && !/<skipped\b/.test(body)) counts.set(className, (counts.get(className) ?? 0) + 1);
    }
  }
  return counts;
}

export function missingClasses(expected, counts) {
  return expected.filter(name => !counts.get(name));
}

function reportFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return reportFiles(path);
    return entry.isFile() && /^TEST-.*\.xml$/.test(entry.name) ? [path] : [];
  });
}

// Accepts an optional trailing "<index>/<count>" (zero-based) selecting one shard.
function selectedClasses(text, shard) {
  const entries = parseSmokeEntries(text);
  if (shard === undefined) return entries.map(entry => entry.name);
  const match = /^(\d+)\/(\d+)$/.exec(shard);
  if (!match) throw new Error(`Shard must look like 0/4, not ${shard}`);
  const classes = shardClasses(entries, Number(match[1]), Number(match[2]));
  if (!classes.length) throw new Error(`Shard ${shard} has no classes; use fewer shards`);
  return classes;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [command, ...args] = process.argv.slice(2);
  try {
    const text = readFileSync(SMOKE_TEST_LIST, 'utf8');
    if (command === 'list' && args.length <= 1) {
      process.stdout.write(`${selectedClasses(text, args[0]).join('\n')}\n`);
    } else if (command === 'verify' && args.length >= 1 && args.length <= 2) {
      const [resultsDirectory, shard] = args;
      const expected = selectedClasses(text, shard);
      const counts = executedTests(reportFiles(resultsDirectory).map(file => readFileSync(file, 'utf8')));
      const missing = missingClasses(expected, counts);
      for (const name of expected) console.log(`${String(counts.get(name) ?? 0).padStart(3)}  ${name}`);
      if (missing.length) {
        console.error(`::error::${missing.length} of ${expected.length} smoke-test classes did not run: ${missing.join(', ')}`);
        process.exitCode = 1;
      } else {
        console.log(`All ${expected.length} smoke-test classes ran.`);
      }
    } else {
      console.error('Usage: android-smoke-tests.mjs list [shard] | verify <results-directory> [shard]   (shard: 0/4)');
      process.exitCode = 2;
    }
  } catch (error) {
    console.error(`::error::${error.message}`);
    process.exitCode = 1;
  }
}
