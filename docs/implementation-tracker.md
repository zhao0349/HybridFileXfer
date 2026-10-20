# Fork implementation and acceptance

This work targets `zhao0349/HybridFileXfer` only. No upstream issue, comment or
pull request is created. Baseline: `14e75ab30b921b845591e49580cffcb51f3d4b9a`.

## Scope

- Safe target paths and collision handling; isolate individual file errors.
- Temporary destinations, explicit completion, optional SHA-256 verification.
- Overwrite/skip/keep-both policies, safe incremental copies and persistent resume.
- Failed data channel recovery, cancellation and bounded network operations.
- Paired, authenticated sessions and binding every data channel to its session.
- Selective direct-su backend from upstream PR 118, retaining the existing
  selector and adding rejection/timeout/lifecycle handling.
- Android explicit transfer controls, truthful progress, history, selection and
  directory navigation; rotation and foreground session lifecycle.
- Desktop graphical connection manager, waiting for devices, requesting Android
  startup, cancellation, retry/backoff, readable errors, diagnostics and history.
- Default locale fallback, configurable local/remote ports, versioned docs,
  reproducible desktop distributions and Android builds.
- Repeatable performance benchmarks; optional ADB Burst Mode, without claiming
  unmeasured speed improvements.

## Excluded after assessment

MPTCP replacement, a new Wi-Fi Direct pairing stack, speculative Wi-Fi 6E/7
switches, and block-size tuning without benchmark evidence were not recommended.
Existing completion summaries, bookmarks, Linux support and UTF-8 scripts remain.

## Verification boundary

Use synthetic data only. Tests cover corruption, interruption, reconnect/resume,
cancellation, destination conflicts, malformed inputs and authorization failure.
CI builds are distinct from Android OEM background, Magisk/KernelSU/APatch,
physical disk/network and multi-display acceptance. Record actual results and
remaining hardware checks; an unrun check is never reported as passed.
