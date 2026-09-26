#!/usr/bin/env bash
# End-to-end check of the pre-commit hooks: builds the wheel carrying the native binary and the npm
# package, installs this repository's hooks the way pre-commit does for a user, and runs both
# families in a throwaway repository.
#
# pre-commit installs a hook repository at a commit, so this tests HEAD, not uncommitted changes.
# Needs sbt, clang, npm, python3 and pre-commit on PATH.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export PRE_COMMIT_HOME="$work/pre-commit-cache"

fail() { echo "FAIL: $*" >&2; exit 1; }

(cd "$root" && sbt -batch cliNative/nativeLink cliJS/npmPackage)
python3 "$root/python/build_wheel.py" --binary "$root/cli/.native/target/scala-3.8.2/hocon-formatter" --out "$work"
wheel=$(ls "$work"/hocon_formatter-*.whl)
tarball="$work/$(npm pack --silent --pack-destination "$work" "$root/cli/.js/target/npm-package")"

consumer="$work/consumer"
mkdir -p "$consumer" && cd "$consumer"
git init -q
# The published versions would come from the hook definitions; this run uses the ones just built.
cat > .pre-commit-config.yaml <<EOF
repos:
  - repo: $root
    rev: $(git -C "$root" rev-parse HEAD)
    hooks:
      - { id: hocon-formatter,            additional_dependencies: ["$wheel"] }
      - { id: hocon-formatter-check,      additional_dependencies: ["$wheel"] }
      - { id: hocon-formatter-node,       additional_dependencies: ["$tarball"] }
      - { id: hocon-formatter-check-node, additional_dependencies: ["$tarball"] }
EOF

unformatted=$(printf 'app {\n    name = "svc"\n   port =8080\n}')
formatted=$(printf 'app {\n  name: svc\n  port: 8080\n}')

# Runs one family of hooks, the formatter and the check, against fresh fixtures.
scenario() {
  local format_id=$1 check_id=$2
  printf '%s\n' "$unformatted" > app.conf
  printf 'a : ${\n' > broken.conf
  printf 'worker_processes 4;\n' > nginx.conf
  git add . && git -c user.name=e2e -c user.email=e2e@example.com commit -q --allow-empty -m "fixture for $format_id"

  pre-commit run "$check_id" --all-files && fail "$check_id passed on an unformatted file"
  [ "$(cat app.conf)" = "$unformatted" ] || fail "$check_id wrote a file"

  # pre-commit fails a hook that modifies files, which is how a commit gets stopped for re-staging.
  pre-commit run "$format_id" --all-files && fail "$format_id reported no changes"
  [ "$(cat app.conf)" = "$formatted" ] || fail "$format_id did not format app.conf: $(cat app.conf)"
  [ "$(cat broken.conf)" = 'a : ${' ] || fail "$format_id wrote a refused file"
  [ "$(cat nginx.conf)" = 'worker_processes 4;' ] || fail "$format_id wrote a non-HOCON .conf file"

  pre-commit run "$check_id" --all-files || fail "$check_id failed after formatting"
  echo "pre-commit hooks $format_id and $check_id: OK"
}

scenario hocon-formatter hocon-formatter-check
scenario hocon-formatter-node hocon-formatter-check-node
