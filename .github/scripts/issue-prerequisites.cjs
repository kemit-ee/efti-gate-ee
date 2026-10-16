'use strict';

const CONTEXT = 'Issue prerequisites';
const LEGACY_ISSUE = 312;
const LEGACY_AUDIT = 313;

function prerequisites(pr, repo = {owner: 'kemit-ee', repo: 'efti-gate-ee'}) {
  // Documentation examples are not actual dependency declarations.
  const body = (pr.body || '').replace(/^\s*(`{3,}|~{3,})[^\n]*\n[\s\S]*?^\s*\1\s*$/gm, '')
    .replace(/`[^`\n]*`/g, '');
  const dependencies = new Set();
  for (const line of body.split(/\r?\n/)) {
    if (!/^\s*Depends-On\s*:/i.test(line)) continue;
    const match = line.match(/^\s*Depends-On:\s*(#[1-9]\d*(?:\s*,\s*#[1-9]\d*)*)\s*$/i);
    if (!match) throw new Error('Use Depends-On: #123, #456 (same-repository issues only).');
    for (const value of match[1].matchAll(/#(\d+)/g)) {
      const number = Number(value[1]);
      if (!Number.isSafeInteger(number)) throw new Error('Invalid dependency issue number.');
      dependencies.add(number);
    }
  }
  const labels = (pr.labels || []).map(label => typeof label === 'string' ? label : label.name);
  const repository = `${repo.owner}/${repo.repo}`.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const reference = `(?:#312\\b|${repository}#312\\b|https://github\\.com/${repository}/issues/312(?:\\b|/))`;
  const closesLegacy = new RegExp(`\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+${reference}`, 'i').test(body);
  if (labels.includes('legacy-removal') || closesLegacy ||
      (pr.closingIssueNumbers || []).includes(LEGACY_ISSUE)) {
    dependencies.add(LEGACY_AUDIT);
  }
  return [...dependencies].sort((a, b) => a - b);
}

function issueFailure(issue, number) {
  if (issue.pull_request) return `#${number} is a pull request, not an issue`;
  if (issue.state !== 'closed' || issue.state_reason !== 'completed') {
    return `#${number} must be closed as completed`;
  }
  return null;
}

async function evaluate(github, repo, pr) {
  const required = prerequisites(pr, repo);
  const failures = [];
  for (const number of required) {
    const {data} = await github.rest.issues.get({...repo, issue_number: number});
    const failure = issueFailure(data, number);
    if (failure) failures.push(failure);
  }
  return {required, failures};
}

async function refresh(github, context, core) {
  const repo = context.repo;
  // A commit status is shared by all PRs with the same head SHA. Evaluate every
  // open PR and aggregate by SHA so an unrelated PR cannot overwrite a blocker.
  const candidates = await github.paginate(github.rest.pulls.list, {...repo, state: 'open', per_page: 100});
  const groups = new Map();
  let errors = 0;
  for (const candidate of candidates) {
    let pr;
    let group;
    try {
      // The list response is current API metadata. Unrelated PRs must not depend
      // on issue/GraphQL availability or another PR's failing prerequisite.
      if (prerequisites(candidate, repo).length === 0) {
        if (!groups.has(candidate.head.sha)) {
          groups.set(candidate.head.sha, {required: new Set(), failures: []});
        }
        core.info(`PR #${candidate.number}: no declared issue prerequisites`);
        continue;
      }
      // Fetch current metadata, not the possibly stale triggering event body.
      ({data: pr} = await github.rest.pulls.get({...repo, pull_number: candidate.number}));
      if (pr.state !== 'open') continue;
      group = groups.get(pr.head.sha);
      if (!group) {
        group = {required: new Set(), failures: []};
        groups.set(pr.head.sha, group);
      }
      await github.rest.repos.createCommitStatus({
        ...repo, sha: pr.head.sha, context: CONTEXT, state: 'pending',
        description: 'Checking current prerequisite issue states',
      });
      const {required, failures} = await evaluate(github, repo, pr);
      // Do not attach a verdict to a head that changed during evaluation.
      const {data: current} = await github.rest.pulls.get({...repo, pull_number: pr.number});
      if (current.state !== 'open' || current.head.sha !== pr.head.sha ||
          current.updated_at !== pr.updated_at) {
        throw new Error('PR changed during evaluation; waiting for the next refresh.');
      }
      required.forEach(n => group.required.add(n));
      group.failures.push(...failures);
      core.info(`PR #${pr.number}: ${failures.length ? failures.join('; ') : 'prerequisites satisfied'}`);
    } catch (error) {
      errors++;
      // The list response still gives us a SHA if the metadata request failed.
      pr = pr || candidate;
      if (!group) {
        group = groups.get(pr.head.sha) || {required: new Set(), failures: []};
        groups.set(pr.head.sha, group);
      }
      if (group) group.failures.push(`PR #${candidate.number}: cannot verify prerequisites`);
      if (pr) {
        await github.rest.repos.createCommitStatus({
          ...repo, sha: pr.head.sha, context: CONTEXT, state: 'failure',
          description: 'Cannot verify prerequisites; inspect the Issue prerequisites workflow',
        }).catch(statusError => core.error(statusError.message));
      }
      core.error(`PR #${candidate.number}: ${error.message}`);
    }
  }
  for (const [sha, group] of groups) {
    const required = [...group.required].sort((a, b) => a - b);
    const description = group.failures.length ? group.failures.join('; ')
      : required.length ? `Completed prerequisites: ${required.map(n => `#${n}`).join(', ')}`
      : 'No declared issue prerequisites';
    await github.rest.repos.createCommitStatus({
      ...repo, sha, context: CONTEXT, state: group.failures.length ? 'failure' : 'success',
      description: description.slice(0, 140),
      target_url: `https://github.com/${repo.owner}/${repo.repo}/${required.length ? `issues/${required[0]}` : 'pulls'}`,
    });
  }
  if (errors) core.setFailed(`Could not verify prerequisites for ${errors} PR(s).`);
}

module.exports = {CONTEXT, prerequisites, issueFailure, evaluate, refresh};
