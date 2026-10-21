#!/usr/bin/env python3
"""Select this workflow run's binaries and record checksums and source provenance."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
from datetime import datetime, timezone


def prepare(build, env):
    sha = env['GITHUB_SHA']
    run_id = env['GITHUB_RUN_ID']
    attempt = env['GITHUB_RUN_ATTEMPT']
    repository = env['GITHUB_REPOSITORY']
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Expected a complete source commit')
    if not run_id.isdigit() or not attempt.isdigit():
        raise ValueError('Invalid workflow run identity')
    tag = f'v4.0.1-canary.{datetime.now(timezone.utc):%Y%m%d}.{run_id}.{attempt}'
    source = build / 'canary-input'
    output = build / 'canary-release'
    output.mkdir(parents=True, exist_ok=False)
    assets = []

    def copy_one(folder, pattern, name=None):
        matches = list((source / folder).rglob(pattern))
        if len(matches) != 1 or not matches[0].is_file() or matches[0].stat().st_size == 0:
            raise ValueError(f'Expected exactly one nonempty {folder}/{pattern}')
        original = matches[0]
        target = output / (name or original.name.replace('4.0.1', tag[1:]))
        if target.exists():
            raise ValueError(f'Duplicate asset: {target.name}')
        shutil.copyfile(original, target)
        with target.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        assets.append({'name': target.name, 'size': target.stat().st_size, 'sha256': digest})

    for runner, system in [('windows-latest', 'windows'), ('ubuntu-24.04', 'linux'),
                           ('macos-latest', 'macos')]:
        copy_one('desktop-' + runner, f'HybridFileXfer-4.0.1-{system}-*.zip')
    copy_one('desktop-ubuntu-24.04', 'HybridFileXfer.jar', f'HybridFileXfer-{tag[1:]}.jar')
    copy_one('android-debug-and-reports', '*.apk', f'HybridFileXfer-{tag[1:]}-android-debug.apk')
    url = f'https://github.com/{repository}/actions/runs/{run_id}'
    manifest = output / 'BUILD-INFO.json'
    manifest.write_text(json.dumps({'tag': tag, 'commit': sha, 'workflow_run': url,
                                   'attempt': attempt, 'assets': assets}, indent=2) + '\n', encoding='utf-8')
    checksums = [f"{a['sha256']}  {a['name']}\n" for a in assets]
    checksums.append(f'{hashlib.sha256(manifest.read_bytes()).hexdigest()}  {manifest.name}\n')
    (output / 'SHA256SUMS.txt').write_text(''.join(checksums), encoding='utf-8')
    (build / 'canary-tag.txt').write_text(tag + '\n', encoding='utf-8')
    notes = f'''Canary preview built and tested in GitHub Actions from `{sha}`.

[Build and test results]({url}) · Protocol **401** · Update phone and desktop together.

- Windows, Linux and macOS ZIPs include Java; the standalone JAR requires Java.
- Android APK is debug-signed, for testing. A different installed signing key requires uninstalling first, which removes app settings.
- CRC32 block checks are always enabled; optional full-file SHA-256 is off by default.
- Verify downloads with SHA256SUMS.txt; BUILD-INFO.json records the exact source and run.
- App version remains 4.0.1-preview; the canary tag and desktop --version commit identify this build.

All three desktop build/test/package jobs and Android assembleDebug, unit tests and lint must pass before publication.
Synthetic and loopback tests do not replace real phone USB/Wi-Fi, root modes, OEM background behavior or display scaling acceptance.
This is a prerelease and is not marked Latest.
'''
    (build / 'canary-notes.md').write_text(notes, encoding='utf-8')
    print(tag)


if __name__ == '__main__':
    prepare(Path('.build'), os.environ)
