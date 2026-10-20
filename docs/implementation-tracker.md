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

## Assessment-to-implementation map

Numbers below refer to the upstream assessment, not issues closed by this fork.

| Feedback | Implemented behavior |
| --- | --- |
| #34, #35, #113 | Portable escaping without silent aliases, path containment, collision policies, per-file failures |
| #42 | Private staging files, durable prefix, atomic publication; never delete the source |
| #5, #115 | Optional final SHA-256 and block integrity checks |
| #36, #113 | Overwrite / skip / keep both; strict incremental comparison uses full SHA-256 |
| #43, #73, #91 | Failed channel retirement and retransmission; persisted, verified prefix resume after reconnect |
| #105, #30 | Explicit direction buttons, confirmed progress and optional pre-scan |
| #84 | Root locale resources and deterministic fallback |
| #90 | Independent configurable phone and ADB local ports; automatic local allocation |
| #93, #74 | Process-scoped sessions, foreground notification, lifecycle recovery, background-policy instructions |
| #112 | Protocol 400 preview docs, commit-identifying build version, reproducible CI packages |
| #4, #10, #68 | GUI waits for phone, optional ADB launch request, cancellation/retry, recent directories |
| #6, #53 | Persistent local summaries and range selection |
| #2, #21, #25, #50 | Three-platform build/package verification, GUI Windows executable, corrected legacy scripts |
| #27, #70 | Storage location entry points with mode-dependent permissions |
| #104, PR #118 | Direct su backend; preserve mode selector, add rejection/timeout/cancellation cleanup |
| #71, #106 | Reproducible encrypted loopback benchmark and opt-in ADB Burst Mode |
| #114 prerequisite | Pairing, command/data authentication and encryption, session-bound channels; private vulnerability details remain unknown |

The desktop GUI manages connections and transfers. File selection still uses the
phone's two-pane browser. This scope does not add a second desktop file manager.

## Verification boundary

Use synthetic data only. Tests cover corruption, interruption, reconnect/resume,
cancellation, destination conflicts, malformed inputs and authorization failure.
CI builds are distinct from Android OEM background, Magisk/KernelSU/APatch,
physical disk/network and multi-display acceptance. Record actual results and
remaining hardware checks; an unrun check is never reported as passed.

## Evidence

- [PR 1](https://github.com/zhao0349/HybridFileXfer/pull/1) targets this fork's `main`.
- [Build 39457991583](https://github.com/zhao0349/HybridFileXfer/actions/runs/39457991583)
  passed for commit `0e9e936`: Windows, Ubuntu, macOS desktop compilation,
  regression and runtime packaging; legacy Windows launcher compilation;
  Android debug build, four JNI ABIs, unit tests and lint.
- The later cleanup-warning fix has a dedicated regression suite covering a real
  Windows metadata-file handle that denies deletion, publication failure, retry,
  receiver option precedence and concurrent destination creation. Its final CI
  result is recorded in the PR.
- Desktop visual smoke uses generated UI data, checks window close/reopen and
  worker cleanup, and deliberately avoids ADB or a real phone.
- Desktop feedback checks cover light/dark switching without losing inputs or
  transfer state, keyboard focus contrast, Chinese log glyphs and copying,
  caret-free read-only output, and the original logo's pixel identity.
  The native Windows save dialog was exercised with cancellation and a synthetic
  Chinese filename; the exported text was read back as UTF-8.
- Protocol 400 now reports the actual file, direction and assigned byte range
  for each busy data channel. Loopback regression checks cover both directions,
  resumed ranges, channel failure, cancellation and terminal idle cleanup.
  Android channel-state tests cover terminal-state and late-callback handling;
  the updated phone UI still needs to be checked on a real device.
- A local benchmark using one 64 MiB file and 200 x 16 KiB files completed with
  SHA-256 and encryption enabled on 1, 2 and 4 loopback channels, with no failed
  files. It is a software/workload check, not a phone-performance claim.

## Remaining device acceptance

1. Install matching preview APK and desktop build; check USB authorization,
   direct network pairing and startup with the desktop opened first.
2. Test normal, Shizuku ADB/ROOT and direct su on actual supported devices,
   including denied/cancelled authorization and retry.
3. Transfer both directions, unplug one channel, interrupt all channels, reconnect
   and resume; compare independent hashes of the resulting files.
4. Rotate, background and lock the phone; stop using the notification, reopen
   the app, and verify no stale listener or service remains.
5. Check external cards, read-only directories, full disks, Unicode/long names
   and the intended Windows display scaling. Filesystems without a safe atomic
   no-replace primitive can reject keep-both/skip publication while retaining
   the staging file; this must not be treated as successful copying.
6. Compare single and combined physical channels on the same files/device/disk,
   with and without the experimental ADB option.
