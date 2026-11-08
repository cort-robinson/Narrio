import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import test from 'node:test';
import {
  DEFAULT_SECONDS, SMOKE_TEST_LIST, executedTests, missingClasses, parseSmokeEntries, parseSmokeTests, shardClasses,
} from './android-smoke-tests.mjs';

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
    'ATest', 'app.narrio.ATest\napp.narrio.ATest', '# only a comment\n', 'app.narrio.ATest 0',
    'app.narrio.ATest 1.5', 'app.narrio.ATest 12 x', 'app.narrio.ATest -3', 'app.narrio.ATest\napp.narrio.ATest 9']) {
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

test('optional seconds weight classes, defaulting when absent', () => {
  assert.deepEqual(parseSmokeEntries('app.narrio.ATest 12\napp.narrio.BTest\t\t7\napp.narrio.CTest\n'), [
    { name: 'app.narrio.ATest', seconds: 12 },
    { name: 'app.narrio.BTest', seconds: 7 },
    { name: 'app.narrio.CTest', seconds: DEFAULT_SECONDS },
  ]);
});

test('shards keep classes whole, cover every class once, and balance by seconds', () => {
  const entries = parseSmokeEntries(readFileSync(SMOKE_TEST_LIST, 'utf8'));
  const names = entries.map(entry => entry.name);
  for (const count of [1, 2, 4, 6, 8]) {
    const shards = Array.from({ length: count }, (_, index) => shardClasses(entries, index, count));
    assert.deepEqual(shards.flat().sort(), [...names].sort(), `${count} shards cover each class exactly once`);
    for (const shard of shards) assert.deepEqual(shard, names.filter(name => shard.includes(name)), 'list order is kept');
    const seconds = shards.map(shard => shard.reduce((sum, name) => sum + entries.find(e => e.name === name).seconds, 0));
    const longest = Math.max(...entries.map(entry => entry.seconds));
    assert.ok(Math.max(...seconds) - Math.min(...seconds) <= longest, `${count} shards are balanced: ${seconds}`);
  }
  // The same input always gives the same split, so a rerun tests the same classes.
  assert.deepEqual(shardClasses(entries, 2, 4), shardClasses(structuredClone(entries), 2, 4));
  for (const [index, count] of [[4, 4], [-1, 4], [0, 0], [0.5, 2]]) {
    assert.throws(() => shardClasses(entries, index, count), /Invalid shard/);
  }
});
