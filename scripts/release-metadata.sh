#!/usr/bin/env bash
set -euo pipefail
printf 'version=0.5.8\n'
printf 'tag=0.5.8-beta\n'
printf 'release_commit=%s\n' "$(git rev-parse HEAD^{commit})"
printf 'current_bump=%s\n' "$(git rev-parse HEAD^{commit})"
printf 'previous_version=0.5.7\n'
printf 'previous_bump=%s\n' "$(git rev-parse HEAD~1^{commit})"
