#!/usr/bin/env python3
"""Conventional Commits check for a commit message file or a single subject line.

Usage: check_commit_msg.py FILE        (commit-msg hook)
       check_commit_msg.py --subject S (CI: pull request titles)
"""

import re
import sys

TYPES = "build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test|deps"
SUBJECT = re.compile(r"^(?P<type>%s)(\([a-z0-9][a-z0-9._/-]*\))?(?P<bang>!)?: \S.{0,99}$" % TYPES)
EXEMPT = re.compile(r"^(Merge |Revert \"|fixup! |squash! |amend! )")


def check_subject(subject):
    if EXEMPT.match(subject):
        return None
    if not SUBJECT.match(subject):
        return (
            "subject must follow Conventional Commits, e.g. 'feat(sync): add status command'\n"
            "allowed types: %s; max 100 characters after the type" % TYPES.replace("|", ", ")
        )
    return None


def main(argv):
    if len(argv) == 3 and argv[1] == "--subject":
        subject = argv[2].strip()
    elif len(argv) == 2:
        with open(argv[1], encoding="utf-8") as f:
            lines = [line for line in f.read().splitlines() if not line.startswith("#")]
        subject = next((line for line in lines if line.strip()), "")
    else:
        print(__doc__, file=sys.stderr)
        return 2
    err = check_subject(subject)
    if err:
        print("error: invalid commit subject: %r\n%s" % (subject, err), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
