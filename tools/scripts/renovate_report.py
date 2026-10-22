#!/usr/bin/env python3
"""Summarizes a Renovate extract report (RENOVATE_REPORT_TYPE=file) per manager and file."""

import json
import sys

with open(sys.argv[1]) as f:
    report = json.load(f)
total = 0
for repo, data in report.get("repositories", {}).items():
    print("repository:", repo)
    for manager, files in sorted(data.get("packageFiles", {}).items()):
        for pf in files:
            deps = pf.get("deps", [])
            print("  %-14s %s (%d)" % (manager, pf.get("packageFile"), len(deps)))
            for d in deps:
                total += 1
                flag = "" if not d.get("skipReason") else "  [skipped: %s]" % d["skipReason"]
                print(
                    "      %-50s %-28s %s%s"
                    % (d.get("depName"), d.get("currentValue") or "", d.get("datasource") or "", flag)
                )
print("total dependencies:", total)
sys.exit(0 if total else 1)
