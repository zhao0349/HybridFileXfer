# Desktop app

The packaged `HybridFileXfer` application opens a desktop window without a
console. The package includes its Java runtime. A standalone JAR can be started
with `java -jar HybridFileXfer.jar`; Java 8 or newer is required.
The FlatLaf interface uses consistent flat controls and rounded cards across
platforms. The UI library is included in both the standalone JAR and app image.
The appearance selector defaults to **System**, follows subsequent OS appearance
changes, and also offers persistent **Light** and **Dark** choices. Switching
appearance preserves connection settings and active transfers. If the desktop
does not expose an appearance preference, the initial fallback is light.
The header, window and packaged application use the original author's logo.

Open the desktop first, choose USB/ADB or a phone network address, and choose a
computer folder. **Wait & connect** keeps the window available until the phone is
ready. In ADB mode the app can open the Android app and request pairing; accept
the request and permissions on the phone. With the pairing field empty, ADB
reads the current phone session's code and port regardless of which app started
first; a retry refreshes both after a phone-side restart. Explicitly entered
codes are retained. The read-only phone endpoint permits only authorized ADB
shell/root UIDs, exposes no code for stopped or connected sessions, and never
starts a service itself. For network mode, enter the pairing code displayed on
the phone. Use matching desktop and Android builds; older phone builds require
a manually entered code.

File selection remains in the phone's two-pane browser. The desktop displays
confirmed progress, per-channel speed, cancellation, the final summary, and
recent transfers. Unknown totals remain explicitly unknown unless pre-scan is
enabled. A disconnected session can reconnect in the same window; it never
silently replays a file transfer.

Advanced settings include conflict handling, SHA-256 verification, resumption,
incremental comparison, pre-scan, and the local ADB port. Local port `0` uses
ADB's atomic automatic allocation; this avoids Windows reserved-port conflicts.
Only the forwarding rule created by this app is removed. Experimental
`ADB_BURST_MODE` affects this app's subprocesses and does not kill or restart the
shared ADB server.

Pairing secrets are not stored in preferences or history. Optional local history
contains up to 100 task summaries in `~/.hybridfilexfer/history.tsv`, without
device identifiers or file paths. The diagnostics tab keeps bounded session logs
in memory; the Save button opens the operating system's native save dialog and
exports UTF-8 text locally when requested. Diagnostics support Chinese text and
can be selected and copied without displaying an editing caret. Diagnostics may
include transferred paths. Keyboard focus uses a contrasting outline on buttons,
input controls and checkboxes; the activity tabs are keyboard accessible.

## Command line

Existing `-c`, `-s`, and `-d` invocations select command-line mode. Run `--help`
for the current complete option list. Examples:

```text
java -jar HybridFileXfer.jar -c adb --launch-phone --wait
java -jar HybridFileXfer.jar -c adb -s DEVICE -d "D:\Transfers" --pairing-code-file code.txt
java -jar HybridFileXfer.jar -c 192.168.1.2 --port 5740 --pairing-code-file code.txt
```

`--wait` retries with a delay capped at 15 seconds. Ctrl+C stops it.
In ADB mode, omitting a code uses the current phone session automatically.
Authentication and protocol-version errors stop automatic retries so the user
can correct them. The GUI's Stop button cancels waiting, a pending handshake,
or an established connection without closing the application.

## Verification

`python tools/build_desktop.py --test --package` builds and tests the app.
The first build downloads FlatLaf 3.7.2 from Maven Central and verifies its pinned
SHA-256; later builds use `.tools/dependencies`. Its Apache license is included.
Run the build once before opening the desktop module in IntelliJ so that its
library path is available; use the Python build for distribution packages.
`DesktopRegressionTest` covers CLI validation, locale fallback, device selection,
retry bounds, cancellation during a silent handshake, reuse after cancellation,
and local history. `DesktopVisualSmokeTest` skips on headless hosts. On a desktop,
run that test without `-Djava.awt.headless=true` to render idle and synthetic
transfer screenshots; it never invokes ADB or connects to a phone.

Synthetic tests do not establish USB/device-driver, OEM background-policy,
display scaling, or real-device transfer acceptance.
