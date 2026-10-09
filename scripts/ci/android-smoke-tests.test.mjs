import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { SMOKE_TEST_LIST, executedTests, missingClasses, parseSmokeTests } from './android-smoke-tests.mjs';

test('the checked-in smoke-test list parses', () => {
  const classes = parseSmokeTests(readFileSync(SMOKE_TEST_LIST, 'utf8'));
  assert.ok(classes.includes('app.narrio.PersistenceAndRenewalTest'));
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
