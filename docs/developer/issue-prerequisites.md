# Issue prerequisites for pull requests

Declare a same-repository issue prerequisite in the PR description on its own line:

```text
Depends-On: #313
```

Multiple prerequisites use `Depends-On: #123, #456` or separate lines. The named
issues must be **closed as completed**. Open/reopened issues, `not_planned`, missing
issues, PR numbers used as issues, malformed declarations and API failures block
the check. References in ordinary discussion do not declare dependencies.

For the A9 legacy removal, a PR with the `legacy-removal` label or a closing
reference to #312 automatically inherits #313 even without a `Depends-On` line.
Apply that label before work starts and keep `Closes #312` in the eventual PR.
Merely mentioning #312 or #313 in an unrelated planning PR does not block it.
The workflow checks declared metadata; it cannot infer the intent of arbitrary
code changes. Reviewers must identify removal work and reject attempts to omit or
remove its dependency metadata. PR creation is later than the process gate that
forbids starting removal implementation; CI does not enforce when coding begins.

## Behaviour

The `Issue prerequisites` workflow publishes a commit status named exactly
**`Issue prerequisites`** on each open PR head. PR edits, commits and label changes
refresh it. Issue closure/reopening/editing/deletion/transfer refresh all open PRs.
Manual dispatch and a ten-minute schedule provide recovery/reconciliation.
Shared head SHAs aggregate requirements from every open PR using that commit.

Updates are event-driven and asynchronous: reopening revokes success on the next
refresh, not atomically with a merge. GitHub may delay scheduled runs. Repository
administrators/bypass actors remain capable of overriding repository rules.

This check verifies issue state, not the human AC evidence, GO record, deployed
version, approved baseline SHA or other CI results. #313's audit process owns
those assertions. Existing backend/DSL/SQL/E2E checks must remain independently
required as appropriate; a successful issue check does not replace them.

The status-writing workflow never executes PR head code. It checks out the trusted
default branch and calls GitHub APIs with read-only issue/PR permissions plus
commit-status write permission. Its action versions are pinned to commit SHAs.
The separate read-only test workflow can run the PR's unit tests.

## Administrator activation (required for merge blocking)

Adding a workflow alone does **not** prevent merging. A repository administrator
must complete this setup after reviewing/merging the implementation:

1. Deploy the workflow and trusted script to the default branch and each intended
   PR target branch (for example `dev` and `main`). `pull_request_target` loads its
   workflow from the target branch; issue events/schedules use the default branch.
2. Ensure GitHub Actions permits the trusted `pull_request_target` workflow and
   its `statuses: write` permission. Do not grant it permission to run PR head code.
3. Dispatch the workflow to publish statuses for open PRs.
4. In the target branch ruleset/branch protection, **add** the required status check
   `Issue prerequisites` from GitHub Actions. Preserve existing required checks and
   review rules. Prevent bypass where the team's policy requires it. Require the
   normal PR review for dependency metadata and policy changes.
5. Verify an open prerequisite blocks merge, closing it as completed permits this
   check, and reopening blocks again after refresh. Verify other required CI still
   blocks independently. Record the ruleset and verification links in #313/#312.

The current implementation does not publish merge-queue (`merge_group`) statuses;
do not enable it as a merge-queue requirement without adding that support.

The repository permission `Maintain` cannot administer branch protection.
Until an administrator activates and verifies the required check, this is visible
CI feedback, **not an enforced merge prohibition**.

## Test locally

```sh
node --test .github/scripts/issue-prerequisites.test.cjs
```

Tests cover declaration errors, inherited legacy prerequisites, non-completed
closure, reopening, API failures, changing heads and shared-SHA isolation.
