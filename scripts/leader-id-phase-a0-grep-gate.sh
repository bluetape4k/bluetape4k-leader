#!/usr/bin/env bash
# Phase A0 grep gate — run before PR1 is merged.
# Output is appended to PR description as an impact table.
# Usage: ./scripts/leader-id-phase-a0-grep-gate.sh
set -euo pipefail

WORKTREE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_ROOT="${WORKTREE_ROOT}/leader-*/src"

echo "=== Phase A0 Grep Gate ==="
echo "Root: ${WORKTREE_ROOT}"
echo ""

run_grep() {
    local label="$1"
    local pattern="$2"
    local include="${3:-*.kt}"
    echo "--- ${label} ---"
    rg --no-heading -n "${pattern}" --glob "${include}" "${WORKTREE_ROOT}/leader-"*"/src" 2>/dev/null || echo "(no matches)"
    echo ""
}

# 1. LeaderLease.leaderId constructor references (positional or named)
run_grep "1. LeaderLease constructor with leaderId= (named arg)" "LeaderLease\(.*leaderId\s*="

# 2. LeaderLease.leaderId property access (dot access)
run_grep "2. .leaderId property access on LeaderLease" "\.leaderId\b"

# 3. LeaderLockHandle.real() with auditLeaderId or leaderId
run_grep "3. LeaderLockHandle.real() call sites" "LeaderLockHandle\.real\("

# 4. LeaderRunResult.Elected with leaderId
run_grep "4. LeaderRunResult.Elected constructor sites" "Elected\("

# 5. LeaderElectionInfo constructor sites
run_grep "5. LeaderElectionInfo constructor sites" "LeaderElectionInfo\("

# 6. fencing-token comparison pattern (.leaderId ==)
run_grep "6. fencing-token comparison: .leaderId ==" "\.leaderId\s*==|\.leaderId\.equals\("

# 7. LeaderLockHandle.real() — positional full arg check
run_grep "7. LeaderLockHandle.real() full positional args" "LeaderLockHandle\.real\(" "*.kt"

echo "=== Gate complete. Copy output above into PR description. ==="
