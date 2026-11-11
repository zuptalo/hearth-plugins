#!/bin/sh
# Turns on the repository's guardrail hooks for this clone.
git config core.hooksPath .githooks && chmod +x .githooks/* 2>/dev/null; echo "Hooks enabled (.githooks)."
