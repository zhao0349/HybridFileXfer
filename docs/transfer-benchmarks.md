# Local transfer benchmarks

Build the current source with `python tools/build_desktop.py --test`, then run:

```text
python tools/benchmark_transfer.py --size-mib 64 --small-files 200 --small-kib 16 --channels 1,2,4 --repetitions 3
```

The tool generates its own local random files, transfers them over encrypted loopback channels,
and deletes only its temporary benchmark directory. CSV results are written to
`.build/benchmarks/transfer.csv`. The benchmark is outside automatic test discovery and does not
upload anything. Set `JAVA_HOME` to an installed JDK if the system Java launcher is unsuitable.

Each result includes source hashing, transfer, durable checkpoints, destination SHA-256
verification and final commit. It deliberately measures complete, verified files rather than
network counters. Single and multiple channels run the same large-file and small-file workloads.
Even repetitions reverse channel order to reduce simple order bias; operating-system caches are
not flushed. Make sure enough temporary disk space is available for the source and each output.

Loopback results measure software, encryption and local storage overhead. They cannot establish
phone USB/Wi-Fi speed, wireless interference, Android storage performance, or HDD throughput.
Repeat the same file sets on the actual target devices, in both directions, with single-channel
and combined-channel configurations before accepting a performance claim.

Protocol 400 hashes the source when verification, resumability, or strict incremental comparison
is enabled. Disabling the optional final verification alone therefore does not remove the source
hash needed to identify a resumable file safely. Interrupted files retain only a checkpointed
prefix for reuse, after comparing that prefix to the current source; equal file sizes are never
used as proof of content equality.
