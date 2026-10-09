import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const SMOKE_TEST_LIST = '.github/android-smoke-tests.txt';
const CLASS_NAME = /^[a-z_][\w]*(\.[a-z_][\w]*)*\.[A-Z][\w$]*$/;

// One fully qualified class per line; blank lines and # comments are ignored.
export function parseSmokeTests(text) {
  const classes = [];
  for (const [index, raw] of text.split(/\r?\n/).entries()) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    if (!CLASS_NAME.test(line)) throw new Error(`Line ${index + 1} is not a fully qualified test class: ${line}`);
    if (classes.includes(line)) throw new Error(`Line ${index + 1} repeats ${line}`);
    classes.push(line);
  }
  if (!classes.length) throw new Error('The smoke-test list is empty');
  return classes;
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

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [command, resultsDirectory] = process.argv.slice(2);
  try {
    const expected = parseSmokeTests(readFileSync(SMOKE_TEST_LIST, 'utf8'));
    if (command === 'list') {
      process.stdout.write(`${expected.join('\n')}\n`);
    } else if (command === 'verify' && resultsDirectory) {
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
      console.error('Usage: android-smoke-tests.mjs list | verify <results-directory>');
      process.exitCode = 2;
    }
  } catch (error) {
    console.error(`::error::${error.message}`);
    process.exitCode = 1;
  }
}
