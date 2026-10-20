# Local transfer benchmarks

Build the current source with `python tools/build_desktop.py --test`, then run:

```text
python tools/benchmark_transfer.py --size-mib 64 --small-files 200 --small-kib 16 --channels 1,2,4 --repetitions 3
python tools/benchmark_transfer.py --size-mib 64 --small-files 200 --small-kib 16 --channels 1,2,4 --repetitions 3 --sha256 --output .build/benchmarks/transfer-sha256.csv
```

The tool generates its own local random files, transfers them over encrypted loopback channels,
and deletes only its temporary benchmark directory. CSV results are written to
`.build/benchmarks/transfer.csv`. The benchmark is outside automatic test discovery and does not
upload anything. Set `JAVA_HOME` to an installed JDK if the system Java launcher is unsuitable.

Each result includes transfer, CRC32 block checks, durable checkpoints and final commit.
With `--sha256`, timing also includes both endpoints' deferred full-file hashing and comparisons.
The CSV records whether SHA-256 was enabled. Single and multiple channels run the same workloads.
Even repetitions reverse channel order to reduce simple order bias; operating-system caches are
not flushed. Make sure enough temporary disk space is available for the source and each output.

Loopback results measure software, encryption and local storage overhead. They cannot establish
phone USB/Wi-Fi speed, wireless interference, Android storage performance, or HDD throughput.
Repeat the same file sets on the actual target devices, in both directions, with single-channel
and combined-channel configurations before accepting a performance claim.

Protocol 401 defaults to no whole-file SHA-256 pass. Interrupted files retain only a checkpointed
prefix for reuse, after comparing that prefix to the current source. Explicit strict incremental
comparison still hashes source files before transfer to compare existing destination candidates;
equal sizes never prove content equality.

## CRC32 buffer cost

`tools/java/BlockChecksumBenchmark.java` measures CRC32 on existing 1 MiB heap buffers
with a 64 MiB working set. It does not copy data or read files. Compile and run it explicitly
with a JDK; it is not a timing-sensitive test or part of normal builds.

On the development Windows amd64 machine with Java 22.0.2 (2026-10-21), seven half-second
samples after warmup measured **6096 MiB/s median**, range **5316–6414 MiB/s**.
This isolates one endpoint's checksum CPU/memory cost. It does not include encryption,
disk, synchronization or network I/O, and does not establish the phone's checksum speed.
There is no guarantee of zero overhead: verify whole-transfer throughput on target hardware.

## Development-machine comparison (2026-10-21)

The encrypted loopback workload above was run on Java 22.0.2 on Windows with SHA
disabled, resume and prescan enabled: one 64 MiB file and 200 files of 16 KiB,
three repetitions per channel count. The baseline is `793af51` (protocol 400).
The 401 snapshot includes batched block/ACK headers and lazy verification leases;
it precedes removal of one redundant claim-path check per staging operation.
The following are medians, including data transfer, checkpoints and publication:

| Channels | 400 large (MiB/s) | 401 large (MiB/s) | 400 small batch (s) | 401 small batch (s) |
| --- | ---: | ---: | ---: | ---: |
| 1 | 45.52 | 37.10 | 13.65 | 17.52 |
| 2 | 34.91 | 40.34 | 15.15 | 17.37 |
| 4 | 48.26 | 44.48 | 18.45 | 17.70 |

These runs were sequential, not interleaved, and did not flush OS caches. Baseline
large-file samples ranged from 29.08 to 57.69 MiB/s and small batches from 13.65 to
21.41 seconds. Some 401 medians are slower, so these measurements do **not** establish
unchanged end-to-end speed. They also cannot attribute the difference to CRC32 alone:
protocol 401 changes staging validation and removes the old initial whole-source
hash in ordinary resume-enabled transfers. Target-device throughput remains an
acceptance item. All 18 cases in each version completed with zero failed files.

The local CSV evidence is `.build/benchmark-baseline/no-sha.csv` and
`.build/benchmarks/401-final-no-sha.csv`. Generated files and local result files
are deliberately excluded from source control.

The matching 401 run with SHA enabled completed all six scenarios (one repetition,
1/2/4 channels) with zero failed files. Large-file end-to-end rates were
23.77/37.16/36.15 MiB/s; the 200-file batches took 29.69/29.87/28.29 seconds,
including deferred verification and publication. This is a completion check, not
a controlled comparison of hashing performance. Evidence:
`.build/benchmarks/401-final-sha.csv`.
