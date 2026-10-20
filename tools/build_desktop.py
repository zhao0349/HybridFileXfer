#!/usr/bin/env python3
"""Compile, test and package the desktop app using an installed JDK."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / ".build"


def java_tool(name):
    suffix = ".exe" if os.name == "nt" else ""
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / (name + suffix)
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise RuntimeError("JDK tool not found: " + name)
    return found


def run(command, **kwargs):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=ROOT, check=True, **kwargs)


def compile_java(sources, destination, classpath=None):
    destination.mkdir(parents=True, exist_ok=True)
    args = ["--release", "8", "-encoding", "UTF-8", "-d", str(destination)]
    if classpath:
        args += ["-cp", str(classpath)]
    args += [str(path) for path in sources]
    arg_file = BUILD / ("javac-test.args" if classpath else "javac.args")
    arg_file.write_text("\n".join('"' + str(arg).replace("\\", "/").replace('"', '\\"') + '"'
                                  for arg in args), encoding="utf-8")
    run([java_tool("javac"), "@" + str(arg_file)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--package", action="store_true")
    args = parser.parse_args()
    source = ROOT / "HybridFileXfer-PC" / "src"
    classes = BUILD / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    compile_java(sorted(source.rglob("*.java")), classes)
    for resource in source.rglob("*"):
        if resource.is_file() and resource.suffix in (".properties", ".png", ".ico"):
            target = classes / resource.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(resource, target)
    jar_dir = BUILD / "jar"
    jar_dir.mkdir(parents=True, exist_ok=True)
    jar_path = jar_dir / "HybridFileXfer.jar"
    run([java_tool("jar"), "cfe", jar_path,
         "top.weixiansen574.hybridfilexfer.Main", "-C", classes, "."])
    run([java_tool("java"), "-jar", jar_path, "--version"], timeout=20)
    run([java_tool("java"), "-jar", jar_path, "--help"], timeout=20)
    if args.test:
        tests = sorted((ROOT / "tests" / "java").rglob("*.java"))
        if not tests:
            raise RuntimeError("No Java regression tests found")
        test_classes = BUILD / "test-classes"
        if test_classes.exists():
            shutil.rmtree(test_classes)
        compile_java(tests, test_classes, classes)
        classpath = os.pathsep.join([str(classes), str(test_classes)])
        reports = BUILD / "test-results"
        reports.mkdir(parents=True, exist_ok=True)
        executed = 0
        for path in tests:
            text = path.read_text(encoding="utf-8")
            if not re.search(r"public\s+static\s+void\s+main\s*\(", text):
                continue
            package = re.search(r"package\s+([\w.]+)\s*;", text)
            name = (package.group(1) + "." if package else "") + path.stem
            print("Test:", name, flush=True)
            result = subprocess.run([java_tool("java"), "-ea", "-Djava.awt.headless=true",
                                     "-cp", classpath, name], cwd=ROOT,
                                    text=True, encoding="utf-8", errors="replace",
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=180)
            (reports / (name + ".txt")).write_text(result.stdout, encoding="utf-8")
            print(result.stdout, flush=True)
            if result.returncode:
                raise RuntimeError("Regression test failed: " + name)
            executed += 1
        if not executed:
            raise RuntimeError("No runnable regression tests found")
        android = ROOT / "HybridFileXfer-Android/app/src/main/java"
        for prefix in ("top/weixiansen574/hybridfilexfer/core",
                       "top/weixiansen574/hybridfilexfer/security", "top/weixiansen574/nio"):
            for pc in (source / prefix).rglob("*.java"):
                peer = android / pc.relative_to(source)
                if not peer.exists() or pc.read_text(encoding="utf-8") != peer.read_text(encoding="utf-8"):
                    raise RuntimeError("PC/Android shared source differs: " + str(pc.relative_to(source)))
    distributions = BUILD / "distributions"
    distributions.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar_path, distributions / jar_path.name)
    if args.package:
        image_dir = BUILD / "app"
        if image_dir.exists():
            shutil.rmtree(image_dir)
        run([java_tool("jpackage"), "--type", "app-image", "--name", "HybridFileXfer",
             "--input", jar_dir, "--main-jar", jar_path.name, "--dest", image_dir,
             "--app-version", "4.0.0", "--vendor", "HybridFileXfer contributors",
             "--java-options", "-Dfile.encoding=UTF-8"])
        platform = {"win32": "windows", "darwin": "macos"}.get(sys.platform, "linux")
        archive = distributions / ("HybridFileXfer-4.0.0-" + platform + ".zip")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
            for path in image_dir.rglob("*"):
                if path.is_file():
                    output.write(path, path.relative_to(image_dir))
        print("Packaged:", archive)


if __name__ == "__main__":
    main()
