import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import test from 'node:test';
import { SMOKE_TEST_LIST, executedTests, missingClasses, parseSmokeTests } from './android-smoke-tests.mjs';

test('the checked-in lists parse, give reasons, and do not overlap', () => {
  const required = parseSmokeTests(readFileSync(SMOKE_TEST_LIST, 'utf8'));
  const deferred = readFileSync('.github/android-nonrequired-tests.txt', 'utf8').split(/\r?\n/)
    .filter(line => line.trim() && !line.startsWith('#'));
  for (const line of deferred) assert.match(line, /^\S+\s+#\s*\S/, `${line} needs a reason`);
  const names = parseSmokeTests(deferred.map(line => line.split('#')[0]).join('\n'));
  assert.deepEqual(names.filter(name => required.includes(name)), []);

  // Every instrumentation test file is either required or deliberately listed as non-required.
  const testFiles = dir => readdirSync(dir, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? testFiles(join(dir, entry.name)) : /\.(kt|java)$/.test(entry.name) ? [join(dir, entry.name)] : []);
  const unlisted = testFiles('app/src/androidTest').flatMap(file => {
    const source = readFileSync(file, 'utf8');
    if (!/@Test\b/.test(source)) return [];
    const name = `${source.match(/^package\s+([\w.]+)/m)[1]}.${basename(file).replace(/\.\w+$/, '')}`;
    return required.includes(name) || names.includes(name) ? [] : [name];
  });
  assert.deepEqual(unlisted, [], 'add each Android test class to one of the lists');
});

test('list parsing ignores comments and rejects ambiguous entries', () => {
  assert.deepEqual(parseSmokeTests('# note\r\n\r\napp.narrio.ATest\n  app.narrio.BTest  \n'),
    ['app.narrio.ATest', 'app.narrio.BTest']);
  for (const text of ['app.narrio.ATest,app.narrio.BTest', 'app.narrio.ATest#method', 'app.narrio',
    'ATest', 'app.narrio.ATest\napp.narrio.ATest', '# only a comment\n']) {
    assert.throws(() => parseSmokeTests(text), undefined, text);
  }
});

test('verification counts executed cases and names every missing class', () => {
  const report = `<testsuites>
    <testsuite name="app.narrio.ATest"><testcase name="a" classname="app.narrio.ATest" time="1" /></testsuite>
    <testsuite name="app.narrio.BTest"><testcase name="b" classname="app.narrio.BTest"><skipped /></testcase></testsuite>
    <testsuite name="app.narrio.CTest"><testcase name="c" classname="app.narrio.CTest"><failure>x</failure></testcase></testsuite>
  </testsuites>`;
  const counts = executedTests([report]);
  assert.equal(counts.get('app.narrio.ATest'), 1);
  assert.equal(counts.get('app.narrio.CTest'), 1);
  assert.deepEqual(missingClasses(['app.narrio.ATest', 'app.narrio.BTest', 'app.narrio.CTest', 'app.narrio.DTest'], counts),
    ['app.narrio.BTest', 'app.narrio.DTest']);
});
