# Run the checks from the command line

The same collection runs headless with Newman, Postman's command-line runner. It is a Compose
service of every gate, `newman`, on the published image `postman/newman:6.1.3-ubuntu`. You don't
install anything.

The service is in the profile `test`, so `up` never starts it. You start it when you want a test run:
on initial setup, after a change, or on a schedule (below).

## Run it

In `local/`:

```bash
docker compose --env-file gates/g1.env run --rm newman
docker compose --env-file gates/g2.env run --rm newman
```

Newman runs inside the gate's own Compose network. It takes the test values from the gate's
Postman environment (`postman/<GATE_ID>.postman_environment.json`) and replaces the host-port URLs
with the gate's service names (`http://ruuter:8086`, `http://ruuter-xroad:8087/xroad`, …). It works
the same on Linux, macOS and Windows.

It prints each request with its tests, then a summary:

```text
┌─────────────────────────┬───────────────────┬──────────────────┐
│                         │          executed │           failed │
├─────────────────────────┼───────────────────┼──────────────────┤
│              iterations │                 1 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│                requests │               119 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│            test-scripts │               118 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│      prerequest-scripts │               143 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│              assertions │               199 │                0 │
├─────────────────────────┴───────────────────┴──────────────────┤
│ total run duration: 8.6s                                       │
└────────────────────────────────────────────────────────────────┘
```

`requests` counts the 118 collection requests plus the login that the admin pre-request script
sends. The exit code is non-zero if any test fails, so you can use the command in CI.

Run it after **both** gates are up: folders 1 and 2 go to the peer gate. If you just restarted a
gate's edelivery, wait for it to be healthy first (`up -d --wait`), or the cross-gate polls come back
empty.

## Pass Newman options

Anything after `newman` goes to Newman. Run one folder:

```bash
docker compose --env-file gates/g1.env run --rm newman --folder "0. Health"
docker compose --env-file gates/g1.env run --rm newman --folder "2. G2G -> peer gate (AS4 via edelivery)"
```

Override a variable:

```bash
docker compose --env-file gates/g1.env run --rm newman --env-var peer_only_plate=TST-001
```

The folder names:

```text
0. Health
1. CA -> Gate (X-Road provider)
2. G2G -> peer gate (AS4 via edelivery)
3. G2G inbound handlers and the AS4 edge
4. Core authority API (gate-internal)
5. Platform -> Gate
6. Administrators
7. Operations
8. Developer mock (ruuter-xroad-mock)
9. Gate internals
```

## Run it on a schedule

A second service, `test-scheduler` (profile `test-schedule`), runs the same collection with
CronManager. Its image is `postman/newman:6.1.3-ubuntu` with the CronManager binary copied from
`turnerrainer/cronmanager:0.2.3-alpha` (`local/test-scheduler.Dockerfile`). Its CronManager config
and the job are inline in `local/compose.yaml`.

```bash
docker compose --env-file gates/g1.env --profile test-schedule up -d test-scheduler
```

| Setting | Default | Where |
|---|---|---|
| Schedule | hourly, `0 0 * * * ?` (Quartz cron, with seconds) | `TEST_SCHEDULE` in the gate env file or the shell |
| Reports | one JUnit XML per run | the gate's volume `test-reports` |
| Run timeout | 15 minutes | `shell_timeout_secs` in the inline config |
| Admin API | `127.0.0.1:<PORT_PREFIX>089`, token `dev-test-scheduler-token` | `TEST_SCHEDULER_TOKEN` |

Run it now, between schedules:

```bash
curl -X POST -H 'Authorization: Bearer dev-test-scheduler-token' \
  http://127.0.0.1:27089/execute/newman/run_collection
```

Read the reports:

```bash
docker run --rm -v efti-g1_test-reports:/r postgres:18 ls /r
# newman-run-report-2026-11-04-07-58-40-713-0.xml
# newman-run-report-2026-11-04-07-59-10-037-0.xml
docker run --rm -v efti-g1_test-reports:/r postgres:18 sh -c 'grep -o "<testsuites[^>]*>" /r/*.xml'
# /r/newman-run-report-…-0.xml:<testsuites name="eFTI Gate" tests="118" time="3.283">
```

A failed run is a failed CronManager job: CronManager logs it at WARN with the exit code
(`docker logs efti-g1-test-scheduler`), and the JUnit file holds every failure. This was run for the
book with `TEST_SCHEDULE='0 * * * * ?'` (every minute) and the admin API: both runs wrote a report
with 118 tests, 0 failures, 0 errors.

Stop it with `docker compose --env-file gates/g1.env --profile test-schedule stop test-scheduler`.

## Reading a failure

Newman prints each failure at the end with the request it belongs to. A run with a wrong internal
token:

```bash
docker compose --env-file gates/g1.env run --rm newman \
  --folder "2. G2G -> peer gate (AS4 via edelivery)" --env-var internal_token=wrong
```

```text
  #  failure         detail
 1.  AssertionError  status 200
                     expected response to have status code 200 but got 401
                     at assertion:0 in test-script
                     inside "2. G2G -> peer gate (AS4 via edelivery) / FTI019 search TST-001"
```

Then look at the logs of the gate that answered:

```bash
docker compose --env-file gates/g1.env logs -f edelivery      # Ctrl-C to stop following
docker logs --since 5m efti-g2-edelivery 2>&1 | grep -i error
```

[Troubleshooting](../reference/troubleshooting.md) lists the failures seen so far and what caused
them.

Next: [What to read next](./next-steps.md).
