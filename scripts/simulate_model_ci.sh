#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-fraud-demo}"
RELEASE="${RELEASE:-fraud-demo}"
VALUES_FILE="${VALUES_FILE:-charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml}"
MODEL="${MODEL:-MODEL_B}"
REGISTERED_MODEL_NAME="${REGISTERED_MODEL_NAME:-fraud-MODEL_B}"
MODEL_VERSION="${MODEL_VERSION:-}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-fraud-model-b}"
IMAGE_TAG="${IMAGE_TAG:-}"
MLFLOW_LOCAL_PORT="${MLFLOW_LOCAL_PORT:-15000}"
RUSTFS_LOCAL_PORT="${RUSTFS_LOCAL_PORT:-19000}"
PUSH_IMAGE="${PUSH_IMAGE:-false}"
KIND_CLUSTER="${KIND_CLUSTER:-}"
MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-}"

usage() {
  cat <<USAGE
Usage: $0 [options]

Options:
  --model MODEL                    Model key in Helm values. Default: ${MODEL}
  --registered-model-name NAME     MLflow registered model. Default: ${REGISTERED_MODEL_NAME}
  --model-version VERSION          MLflow model version to package. Required unless MODEL_VERSION is set.
  --image-repository REPOSITORY    Predictor image repository. Default: ${IMAGE_REPOSITORY}
  --image-tag TAG                  Predictor image tag. Defaults to mlflow-v<VERSION>.
  --namespace NAMESPACE            Kubernetes namespace. Default: ${NAMESPACE}
  --release RELEASE                Helm release. Default: ${RELEASE}
  --values-file FILE               Helm values file. Default: ${VALUES_FILE}
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --registered-model-name) REGISTERED_MODEL_NAME="$2"; shift 2 ;;
    --model-version) MODEL_VERSION="$2"; shift 2 ;;
    --image-repository) IMAGE_REPOSITORY="$2"; shift 2 ;;
    --image-tag) IMAGE_TAG="$2"; shift 2 ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --release) RELEASE="$2"; shift 2 ;;
    --values-file) VALUES_FILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "${MODEL_VERSION}" ]]; then
  echo "--model-version is required" >&2
  exit 2
fi
if [[ -z "${IMAGE_TAG}" ]]; then
  IMAGE_TAG="mlflow-v${MODEL_VERSION}"
fi

for binary in kubectl helm docker; do
  command -v "${binary}" >/dev/null || {
    echo "Missing required command: ${binary}" >&2
    exit 127
  }
done

cleanup_pids=()
cleanup() {
  for pid in "${cleanup_pids[@]:-}"; do
    kill "${pid}" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

svc_by_component() {
  local component="$1"
  kubectl get svc -n "${NAMESPACE}" \
    -l "app.kubernetes.io/component=${component}" \
    -o jsonpath='{.items[0].metadata.name}'
}

secret_by_component() {
  local component="$1"
  kubectl get secret -n "${NAMESPACE}" \
    -l "app.kubernetes.io/component=${component}" \
    -o jsonpath='{.items[0].metadata.name}'
}

MLFLOW_SVC="$(svc_by_component mlflow)"
RUSTFS_SVC="$(svc_by_component rustfs)"
RUSTFS_SECRET="$(secret_by_component rustfs)"

kubectl port-forward -n "${NAMESPACE}" "svc/${MLFLOW_SVC}" "${MLFLOW_LOCAL_PORT}:5000" >/tmp/fraud-mlflow-port-forward.log 2>&1 &
cleanup_pids+=("$!")
kubectl port-forward -n "${NAMESPACE}" "svc/${RUSTFS_SVC}" "${RUSTFS_LOCAL_PORT}:9000" >/tmp/fraud-rustfs-port-forward.log 2>&1 &
cleanup_pids+=("$!")
sleep 3

export MLFLOW_TRACKING_URI="http://127.0.0.1:${MLFLOW_LOCAL_PORT}"
export MLFLOW_S3_ENDPOINT_URL="http://127.0.0.1:${RUSTFS_LOCAL_PORT}"
export AWS_ACCESS_KEY_ID="$(
  kubectl get secret -n "${NAMESPACE}" "${RUSTFS_SECRET}" -o jsonpath='{.data.access-key}' | base64 --decode
)"
export AWS_SECRET_ACCESS_KEY="$(
  kubectl get secret -n "${NAMESPACE}" "${RUSTFS_SECRET}" -o jsonpath='{.data.secret-key}' | base64 --decode
)"
export AWS_DEFAULT_REGION=us-east-1

WORKDIR="$(mktemp -d)"
mkdir -p "${WORKDIR}/context/model"
if command -v mlflow >/dev/null; then
  mlflow artifacts download \
    --artifact-uri "models:/${REGISTERED_MODEL_NAME}/${MODEL_VERSION}" \
    --dst-path "${WORKDIR}/downloaded"
else
  docker run --rm \
    -v "${WORKDIR}:${WORKDIR}" \
    -e MLFLOW_TRACKING_URI="http://host.docker.internal:${MLFLOW_LOCAL_PORT}" \
    -e MLFLOW_S3_ENDPOINT_URL="http://host.docker.internal:${RUSTFS_LOCAL_PORT}" \
    -e AWS_ACCESS_KEY_ID \
    -e AWS_SECRET_ACCESS_KEY \
    -e AWS_DEFAULT_REGION \
    fraud-training:local \
    mlflow artifacts download \
      --artifact-uri "models:/${REGISTERED_MODEL_NAME}/${MODEL_VERSION}" \
      --dst-path "${WORKDIR}/downloaded"
fi

MODEL_DIR="$(find "${WORKDIR}/downloaded" -name MLmodel -exec dirname {} \; | head -n 1)"
if [[ -z "${MODEL_DIR}" ]]; then
  echo "Downloaded MLflow artifact does not contain an MLmodel file" >&2
  exit 1
fi
cp -R "${MODEL_DIR}/." "${WORKDIR}/context/model/"
cp training/serving/Dockerfile "${WORKDIR}/context/Dockerfile"
cp training/serving/server.py "${WORKDIR}/context/server.py"
cp training/serving/requirements.txt "${WORKDIR}/context/requirements.txt"

IMAGE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"
docker build -t "${IMAGE}" "${WORKDIR}/context"

if [[ "${PUSH_IMAGE}" == "true" ]]; then
  docker push "${IMAGE}"
elif [[ -n "${KIND_CLUSTER}" ]]; then
  kind load docker-image "${IMAGE}" --name "${KIND_CLUSTER}"
elif [[ -n "${MINIKUBE_PROFILE}" ]]; then
  minikube -p "${MINIKUBE_PROFILE}" image load "${IMAGE}"
else
  echo "Image built locally as ${IMAGE}. Set KIND_CLUSTER, MINIKUBE_PROFILE, or PUSH_IMAGE=true if your cluster cannot see the local Docker image."
fi

cat >"${WORKDIR}/promotion-values.yaml" <<YAML
models:
  ${MODEL}:
    predictorCommand: []
    predictorArgs: []
    predictorEnv: []
YAML

helm upgrade "${RELEASE}" ./charts/fraud-inference-demo \
  --namespace "${NAMESPACE}" \
  -f "${VALUES_FILE}" \
  -f "${WORKDIR}/promotion-values.yaml" \
  --set trainingJob.enabled=false \
  --set-string "models.${MODEL}.predictorImage.repository=${IMAGE_REPOSITORY}" \
  --set-string "models.${MODEL}.predictorImage.tag=${IMAGE_TAG}" \
  --set-string "models.${MODEL}.predictorImage.pullPolicy=IfNotPresent" \
  --set-string "models.${MODEL}.modelVersion=mlflow-${MODEL_VERSION}"

kubectl rollout restart -n "${NAMESPACE}" deployment \
  -l app.kubernetes.io/component=transaction-events
kubectl rollout status -n "${NAMESPACE}" deployment \
  -l app.kubernetes.io/component=transaction-events --timeout=300s

LOWER_MODEL="$(echo "${MODEL}" | tr '[:upper:]_' '[:lower:]-')"
ISVC_NAME="$(kubectl get inferenceservice -n "${NAMESPACE}" \
  -l "fraud.example.com/model=${MODEL}" \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
if [[ -n "${ISVC_NAME}" ]]; then
  for attempt in {1..150}; do
    ready_status="$(kubectl get inferenceservice -n "${NAMESPACE}" "${ISVC_NAME}" \
      -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    if [[ "${ready_status}" == "True" ]]; then
      break
    fi
    if [[ "${attempt}" == "150" ]]; then
      echo "InferenceService ${ISVC_NAME} did not become Ready; last status=${ready_status}" >&2
      exit 1
    fi
    sleep 2
  done
else
  echo "No InferenceService found for ${MODEL}; expected one similar to ${LOWER_MODEL}" >&2
fi

echo "DEPLOYED_MODEL=${MODEL}"
echo "DEPLOYED_MODEL_VERSION=mlflow-${MODEL_VERSION}"
echo "DEPLOYED_IMAGE=${IMAGE}"
