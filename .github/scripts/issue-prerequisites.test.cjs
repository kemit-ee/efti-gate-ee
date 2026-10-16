'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {prerequisites, evaluate, refresh} = require('./issue-prerequisites.cjs');

test('multiple explicit same-repository prerequisites are deduplicated', () => {
  assert.deepEqual(prerequisites({body: 'Depends-On: #12, #313\nDepends-On: #12'}), [12, 313]);
});

test('malformed declarations fail instead of silently disabling the gate', () => {
  for (const body of ['Depends-On:', 'Depends-On: issue313', 'Depends-On: #0',
    'Depends-On: #313 and #314', 'Depends-On: https://example.com/issues/313']) {
    assert.throws(() => prerequisites({body}));
  }
});

test('legacy-removal label and closing #312 inherit #313', () => {
  for (const pr of [{labels: [{name: 'legacy-removal'}]}, {body: 'Closes #312'},
    {body: 'Fixes #312'}, {closingIssueNumbers: [312]}]) {
    assert.deepEqual(prerequisites(pr), [313]);
  }
  assert.deepEqual(prerequisites({body: 'Related: #312 and #313'}), []);
  assert.deepEqual(prerequisites({body: 'Closes https://github.com/kemit-ee/efti-gate-ee/issues/312'}), [313]);
  assert.deepEqual(prerequisites({body: 'Resolves kemit-ee/efti-gate-ee#312'}), [313]);
  assert.deepEqual(prerequisites({body: 'Closes other/repo#312'}), []);
  assert.deepEqual(prerequisites({body: 'Closes #3120'}), []);
});

test('documentation and fenced examples do not declare dependencies', () => {
  const body = 'Example: `Closes #312`\n```text\nDepends-On: #313\nCloses #312\n```\nRelated: #313';
  assert.deepEqual(prerequisites({body}), []);
});

test('open, reopened, not-planned, deleted and PR dependencies cannot pass', async () => {
  for (const issue of [{state: 'open'}, {state: 'open', state_reason: 'reopened'},
    {state: 'closed', state_reason: 'not_planned'}, {state: 'closed'},
    {state: 'closed', state_reason: 'completed', pull_request: {}}]) {
    const github = {rest: {issues: {get: async () => ({data: issue})}}};
    assert.equal((await evaluate(github, {}, {body: 'Depends-On: #313'})).failures.length, 1);
  }
  const github = {rest: {issues: {get: async () => {throw new Error('404');}}}};
  await assert.rejects(evaluate(github, {}, {body: 'Depends-On: #313'}), /404/);
});

test('only closed-as-completed issues pass and every prerequisite is read', async () => {
  const seen = [];
  const github = {rest: {issues: {get: async ({issue_number}) => {
    seen.push(issue_number);
    return {data: {state: 'closed', state_reason: 'completed'}};
  }}}};
  const result = await evaluate(github, {}, {body: 'Depends-On: #313, #10'});
  assert.deepEqual(seen, [10, 313]);
  assert.deepEqual(result.failures, []);
});

function harness(issue) {
  const statuses = [];
  const errors = [];
  const pr = {number: 10, state: 'open', body: 'Depends-On: #313', labels: [],
    head: {sha: 'head-sha'}, updated_at: '2026-10-17T10:00:00Z'};
  const github = {
    paginate: async () => [pr],
    graphql: async () => ({repository: {pullRequest: {closingIssuesReferences: {
      nodes: [], pageInfo: {hasNextPage: false},
    }}}}),
    rest: {
      pulls: {list: () => {}, get: async () => ({data: pr})},
      issues: {get: async () => {
        if (issue instanceof Error) throw issue;
        return {data: issue};
      }},
      repos: {createCommitStatus: async status => {statuses.push(status);}},
    },
  };
  const context = {repo: {owner: 'kemit-ee', repo: 'efti-gate-ee'}, payload: {}};
  const core = {info: () => {}, error: message => errors.push(message),
    setFailed: message => errors.push(message)};
  return {github, context, core, statuses, errors, pr};
}

test('issue refresh revokes an earlier success on reopening', async () => {
  const h = harness({state: 'closed', state_reason: 'completed'});
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses.at(-1).state, 'success');
  h.github.rest.issues.get = async () => ({data: {state: 'open', state_reason: 'reopened'}});
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses.at(-1).state, 'failure');
  assert.equal(h.statuses.at(-1).sha, 'head-sha');
});

test('API failures publish failure rather than leaving a green status', async () => {
  const h = harness(new Error('API unavailable'));
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses[0].state, 'pending');
  assert.equal(h.statuses.at(-1).state, 'failure');
  assert.ok(h.errors.length);
});

test('PR head changes during evaluation cannot receive a successful verdict', async () => {
  const h = harness({state: 'closed', state_reason: 'completed'});
  let reads = 0;
  h.github.rest.pulls.get = async () => ({data: ++reads === 1 ? h.pr
    : {...h.pr, head: {sha: 'new-head'}}});
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses.at(-1).state, 'failure');
  assert.ok(!h.statuses.some(s => s.state === 'success'));
});

test('another PR on the same SHA cannot overwrite a blocked dependency', async () => {
  const h = harness({state: 'open'});
  const other = {...h.pr, number: 11, body: ''};
  h.github.paginate = async () => [h.pr, other];
  h.github.rest.pulls.get = async ({pull_number}) => ({data: pull_number === 10 ? h.pr : other});
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses.at(-1).state, 'failure');
  assert.ok(!h.statuses.some(s => s.state === 'success'));
});

test('failed PR metadata fetch revokes success on the listed head SHA', async () => {
  const h = harness({state: 'closed', state_reason: 'completed'});
  h.github.rest.pulls.get = async () => {throw new Error('metadata unavailable');};
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses.at(-1).state, 'failure');
  assert.equal(h.statuses.at(-1).sha, 'head-sha');
});

test('unrelated PR passes without issue, GraphQL or metadata detail API calls', async () => {
  const h = harness(new Error('issue API unavailable'));
  h.pr.body = 'Ordinary change. Related: #312, #313';
  h.github.graphql = async () => {throw new Error('GraphQL unavailable');};
  h.github.rest.pulls.get = async () => {throw new Error('metadata detail unavailable');};
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses.at(-1).state, 'success');
  assert.equal(h.statuses.at(-1).description, 'No declared issue prerequisites');
  assert.deepEqual(h.errors, []);
});

test('a blocked legacy PR does not block an unrelated PR on another SHA', async () => {
  const h = harness({state: 'open'});
  const other = {...h.pr, number: 11, body: 'Ordinary change', head: {sha: 'unrelated-sha'}};
  h.github.paginate = async () => [h.pr, other];
  await refresh(h.github, h.context, h.core);
  assert.equal(h.statuses.filter(s => s.sha === 'head-sha').at(-1).state, 'failure');
  assert.equal(h.statuses.filter(s => s.sha === 'unrelated-sha').at(-1).state, 'success');
});
