#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

FEAST_REPO_DIR="${FEAST_REPO_DIR:-${REPO_ROOT}/feast}"
VENV_DIR="${FEAST_UI_VENV_DIR:-/tmp/fraud-feast-ui-venv}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8888}"
DETACH="${DETACH:-false}"
APPLY="${APPLY:-true}"
LOG_FILE="${LOG_FILE:-/tmp/fraud-feast-ui.log}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if [[ ! -f "${FEAST_REPO_DIR}/feature_store.yaml" ]]; then
  echo "Feast repository not found at ${FEAST_REPO_DIR}" >&2
  exit 1
fi

if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "Python executable not found: ${PYTHON_BIN}" >&2
  exit 1
fi

if ! command -v lsof >/dev/null 2>&1; then
  echo "lsof is required to check whether ${HOST}:${PORT} is already in use" >&2
  exit 1
fi

if lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port ${PORT} is already in use. Set PORT=<free-port> or stop the existing process." >&2
  lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN >&2
  exit 1
fi

if [[ ! -x "${VENV_DIR}/bin/feast" ]]; then
  echo "Creating Feast UI virtualenv at ${VENV_DIR}"
  "${PYTHON_BIN}" -m venv "${VENV_DIR}"
  "${VENV_DIR}/bin/python" -m pip install --upgrade pip
  "${VENV_DIR}/bin/python" -m pip install -r "${FEAST_REPO_DIR}/requirements.txt"
fi

export FEAST_REPO_PATH="${FEAST_REPO_DIR}"

if [[ "${APPLY}" == "true" ]]; then
  echo "Applying Feast definitions from ${FEAST_REPO_DIR}"
  (
    cd "${FEAST_REPO_DIR}"
    "${VENV_DIR}/bin/feast" apply
  )
fi

echo "Starting Feast UI at http://${HOST}:${PORT}"

if [[ "${DETACH}" == "true" ]]; then
  mkdir -p "$(dirname "${LOG_FILE}")"
  pid="$("${PYTHON_BIN}" - "${FEAST_REPO_DIR}" "${VENV_DIR}/bin/feast" "${HOST}" "${PORT}" "${LOG_FILE}" <<'PY'
import os
import subprocess
import sys

repo_dir, feast_bin, host, port, log_file = sys.argv[1:]
env = os.environ.copy()
env["FEAST_REPO_PATH"] = repo_dir
cmd = [feast_bin, "ui", "--host", host, "--port", port]

with open(log_file, "ab", buffering=0) as out:
    proc = subprocess.Popen(
        cmd,
        cwd=repo_dir,
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=out,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )

print(proc.pid)
PY
)"
  echo "Feast UI PID: ${pid}"
  echo "Log file: ${LOG_FILE}"
  echo "Stop with: kill ${pid}"
else
  cd "${FEAST_REPO_DIR}"
  exec "${VENV_DIR}/bin/feast" ui --host "${HOST}" --port "${PORT}"
fi
