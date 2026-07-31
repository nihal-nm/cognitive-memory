#!/usr/bin/env python3
"""SpotBugs Reporter — parse SpotBugs XML and emit GitHub Actions annotations.

Usage: spotbugs_reporter.py <glob-pattern>

Reads SpotBugs XML reports matching the glob pattern and emits ::error or
::warning workflow commands so GitHub renders them as inline PR annotations.
Exits with code 1 if any bugs are found.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

LIMITS = {"error": 10, "warning": 10, "total": 50}

CATEGORY_SEVERITY = {
    "CORRECTNESS": "error",
    "SECURITY": "error",
    "MALICIOUS_CODE": "error",
    "MT_CORRECTNESS": "error",
    "PERFORMANCE": "warning",
    "STYLE": "warning",
    "BAD_PRACTICE": "warning",
    "I18N": "warning",
    "EXPERIMENTAL": "warning",
}


def resolve_source_path(src_dirs, class_name, start_line):
    """Resolve a class name to a relative source file path."""
    rel_path = class_name.replace(".", os.sep) + ".java"
    for src_dir in src_dirs:
        candidate = os.path.join(src_dir, rel_path)
        if os.path.isfile(candidate):
            return os.path.relpath(candidate)
    return rel_path


def parse_report(path):
    """Parse a single SpotBugs XML report and return a list of findings."""
    tree = ET.parse(path)
    root = tree.getroot()

    src_dirs = []
    project = root.find("Project")
    if project is not None:
        for src_dir in project.findall("SrcDir"):
            if src_dir.text:
                src_dirs.append(src_dir.text)

    findings = []
    for bug in root.findall("BugInstance"):
        bug_type = bug.get("type", "UNKNOWN")
        category = bug.get("category", "STYLE")
        priority = int(bug.get("priority", "2"))

        short_msg_el = bug.find("ShortMessage")
        long_msg_el = bug.find("LongMessage")
        message = ""
        if long_msg_el is not None and long_msg_el.text:
            message = long_msg_el.text
        elif short_msg_el is not None and short_msg_el.text:
            message = short_msg_el.text

        source_line = bug.find(".//SourceLine")
        if source_line is None:
            continue

        class_name = source_line.get("classname", "")
        source_path = source_line.get("sourcepath", "")
        start = source_line.get("start", "0")
        line = int(start) if start else 0

        if source_path and src_dirs:
            file_path = resolve_source_path(src_dirs, class_name, line)
        elif source_path:
            file_path = source_path
        else:
            file_path = class_name.replace(".", os.sep) + ".java"

        severity = CATEGORY_SEVERITY.get(category, "warning")
        if priority <= 1:
            severity = "error"

        findings.append({
            "file": file_path,
            "line": line,
            "severity": severity,
            "type": bug_type,
            "category": category,
            "message": message,
        })

    return findings


def main():
    if len(sys.argv) < 2:
        print("Usage: spotbugs_reporter.py <glob-pattern>", file=sys.stderr)
        sys.exit(2)

    pattern = sys.argv[1]
    files = sorted(glob.glob(pattern, recursive=True))
    if not files:
        print(f"No SpotBugs reports found matching: {pattern}")
        return

    all_findings = []
    for f in files:
        try:
            all_findings.extend(parse_report(f))
        except ET.ParseError as e:
            print(f"::warning::Failed to parse SpotBugs XML {f}: {e}")

    if not all_findings:
        print("SpotBugs: no issues found.")
        return

    counters = {"error": 0, "warning": 0, "total": 0, "skipped": 0}

    for finding in all_findings:
        counters["total"] += 1
        sev = finding["severity"]
        counters[sev] = counters.get(sev, 0) + 1

        if counters["total"] <= LIMITS["total"] and counters[sev] <= LIMITS.get(sev, LIMITS["total"]):
            print(
                f"::{sev} file={finding['file']},line={finding['line']},"
                f"title={finding['type']}::{finding['message']}"
            )
        else:
            counters["skipped"] += 1

    print(f"\n=== SpotBugs Summary ===")
    print(f"Total bugs found: {counters['total']}")
    if counters["skipped"] > 0:
        reported = counters["total"] - counters["skipped"]
        print(f"Reported as annotations: {reported}")
        print(f"Omitted due to limits: {counters['skipped']}")
    print(f"Errors: {counters['error']}")
    print(f"Warnings: {counters['warning']}")
    print(f"========================\n")

    for finding in all_findings:
        print(
            f"{finding['file']}:{finding['line']} "
            f"({finding['type']}) {finding['message']}"
        )

    sys.exit(1)


if __name__ == "__main__":
    main()
