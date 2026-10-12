#!/usr/bin/env sh
# Structural verification used ONLY in offline (fixture) mode.
#
# Offline runs exercise the orchestration machinery with hand-written fixtures and no network, so the
# real build (./gradlew test) can't resolve Spring dependencies. This script is a deliberately cheap
# stand-in "compile check": every Java file must declare a package and have balanced braces, test
# classes must exist, and the change must ship a README. Live runs (--llm gemini) use the real build.
#
# Usage: offline-verify.sh [path-prefix]   (limit the check to one module, e.g. url-shortener-service/)
set -u
scope="${1:-}"
spec="${scope:-.}"
status=0

for f in $(git ls-files --cached --others --exclude-standard -- "${scope}*.java" "${scope}**/*.java" | sort -u); do
  grep -q '^package ' "$f" || { echo "$f: error: missing package declaration"; status=1; }
  # Count braces in code only: strip string and char literals first.
  code=$(sed -E -e "s/'([^'\\]|\\.)'//g" -e 's/"([^"\\]|\\.)*"//g' "$f")
  open=$(printf '%s' "$code" | tr -cd '{' | wc -c)
  close=$(printf '%s' "$code" | tr -cd '}' | wc -c)
  if [ "$open" -ne "$close" ]; then
    echo "$f: error: unbalanced braces ($open '{' vs $close '}') — class does not compile"
    status=1
  fi
done

if ! git ls-files --cached --others --exclude-standard -- "${spec}" | grep -Eq '/test/.*Test\.java$'; then
  echo "error: no test classes found"
  status=1
fi

if ! git ls-files --cached --others --exclude-standard -- "${spec}" | grep -Eq '(^|/)README\.md$'; then
  echo "error: no README.md"
  status=1
fi

[ "$status" -eq 0 ] && echo "offline structural verification passed"
exit "$status"
