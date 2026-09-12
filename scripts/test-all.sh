#!/usr/bin/env bash
#
# Deliverable 3: "Test suite runnable with one command, with a coverage report."
#
#   ./scripts/test-all.sh
#
# Builds and tests every backend module plus the frontend, and produces a
# coverage report for each. No infrastructure is required: the backend tests
# use H2 and mocked clients, and the frontend tests use jsdom, so this runs on
# a laptop with nothing started.
#
# Options:
#   --backend-only    skip the frontend
#   --frontend-only   skip the backend
#   --skip-coverage   run tests without generating coverage reports (faster)
#   --open            open the coverage reports in a browser when done

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RUN_BACKEND=true
RUN_FRONTEND=true
COVERAGE=true
OPEN_REPORTS=false

for arg in "$@"; do
  case "$arg" in
    --backend-only)  RUN_FRONTEND=false ;;
    --frontend-only) RUN_BACKEND=false ;;
    --skip-coverage) COVERAGE=false ;;
    --open)          OPEN_REPORTS=true ;;
    -h|--help)       sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

# Angular writes its report under an extra directory named after the project,
# so the frontend path is coverage/frontend/, not coverage/.
FRONTEND_REPORT="frontend/coverage/frontend/index.html"

# Opens a file in the default browser, on macOS or Linux. Silent no-op if
# neither opener exists (e.g. in CI), so --open can never fail a test run.
open_in_browser() {
  local file="$1"
  [ -f "$file" ] || return 0
  if command -v open >/dev/null 2>&1; then
    open "$file"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$file" >/dev/null 2>&1
  fi
}

# shared-contracts and service-commons are dependencies of every service, so
# they must be installed to the local repo before anything else resolves.
LIBRARIES=(shared-contracts service-commons)
SERVICES=(ingestion archive search case hold-retention export audit)

bold() { printf "\033[1m%s\033[0m\n" "$1"; }
green() { printf "\033[32m%s\033[0m\n" "$1"; }

FAILED=()

run_maven_module() {
  local dir="$1" name="$2"
  printf "  %-26s" "$name"
  # Not -q: the surefire summary is what we report back, and it is suppressed
  # in quiet mode.
  if (cd "$dir" && mvn -B verify >/tmp/dh-test-$name.log 2>&1); then
    local tests
    tests=$(grep -hoE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+" \
            "/tmp/dh-test-$name.log" 2>/dev/null | tail -1 || true)
    green "PASS  ${tests:-no tests}"
  else
    printf "\033[31mFAIL\033[0m\n"
    tail -30 "/tmp/dh-test-$name.log"
    FAILED+=("$name")
  fi
}

if [ "$RUN_BACKEND" = true ]; then
  bold "Building shared libraries"
  for lib in "${LIBRARIES[@]}"; do
    printf "  %-26s" "$lib"
    if (cd "services/$lib" && mvn -B -q install -DskipTests >/dev/null 2>&1); then
      green "installed"
    else
      printf "\033[31mFAIL\033[0m\n"
      FAILED+=("$lib (install)")
    fi
  done

  bold "Backend tests"
  for lib in "${LIBRARIES[@]}"; do
    run_maven_module "services/$lib" "$lib"
  done
  for svc in "${SERVICES[@]}"; do
    run_maven_module "services/$svc-service" "$svc-service"
  done
  run_maven_module "data-generator" "data-generator"
fi

if [ "$RUN_FRONTEND" = true ]; then
  bold "Frontend tests"
  if [ ! -d frontend/node_modules ]; then
    echo "  installing dependencies…"
    (cd frontend && npm ci >/dev/null 2>&1 || npm install >/dev/null 2>&1)
  fi
  if [ "$COVERAGE" = true ]; then
    (cd frontend && npm run test:coverage) || FAILED+=("frontend")
  else
    (cd frontend && npm test -- --watch=false) || FAILED+=("frontend")
  fi
fi

echo
if [ ${#FAILED[@]} -gt 0 ]; then
  printf "\033[31mFAILED: %s\033[0m\n" "${FAILED[*]}"
  exit 1
fi

bold "All tests passed."
if [ "$COVERAGE" = true ]; then
  echo
  echo "Coverage reports:"
  for report in services/*/target/site/jacoco/index.html; do
    [ -f "$report" ] && echo "  $report"
  done
  [ -f "$FRONTEND_REPORT" ] && echo "  $FRONTEND_REPORT"
  echo
  echo "Open them all with:   ./scripts/test-all.sh --open"

  if [ "$OPEN_REPORTS" = true ]; then
    echo
    echo "Opening reports…"
    for report in services/*/target/site/jacoco/index.html; do
      open_in_browser "$report"
    done
    open_in_browser "$FRONTEND_REPORT"
  fi
fi
