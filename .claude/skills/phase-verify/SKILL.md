---
name: phase-verify
description: Execute a phase's Verification section verbatim and report a pass/fail table with evidence. Use at a phase boundary, or when asked whether a phase is actually done.
---

# Verify a phase

Run `agent/phase-verify.ps1 -Phase <phase-file>` first. It automates the mechanical checks
— config hygiene, container health, the topic catalogue — and prints the phase's own
`## Verification` block.

Then do by hand everything it reported as `n/a` or could not check. That residue is the
point of this skill: the Kafka UI consumer-group count, the actual claims inside a token,
`--describe` output matching `cleanup.policy` and `retention.ms`, a balance that is visibly
correct in the UI.

## Report a table

| Check | Result | Evidence |
|---|---|---|

`Evidence` holds the actual output — a count, a claim value, a command's stdout. **Never
record PASS without it.** A check you could not run is reported as such, with the reason;
it is never reported as passing.

Finish with a one-line verdict against the phase's `## Done when` list, and name any item
that is not met.
