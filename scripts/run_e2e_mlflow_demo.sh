#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-fraud-demo}"
RELEASE="${RELEASE:-fraud-demo}"
VALUES_FILE="${VALUES_FILE:-charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml}"
RESET="${RESET:-true}"
MODEL="${MODEL:-MODEL_B}"
REGISTERED_MODEL_NAME="${REGISTERED_MODEL_NAME:-fraud-MODEL_B}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
TX_LOCAL_PORT="${TX_LOCAL_PORT:-18080}"
KIND_CLUSTER="${KIND_CLUSTER:-}"
MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-}"

for binary in kubectl helm docker curl mvn; do
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

load_image() {
  local image="$1"
  if [[ -n "${KIND_CLUSTER}" ]]; then
    kind load docker-image "${image}" --name "${KIND_CLUSTER}"
  elif [[ -n "${MINIKUBE_PROFILE}" ]]; then
    minikube -p "${MINIKUBE_PROFILE}" image load "${image}"
  else
    echo "Built ${image}; assuming the Kubernetes runtime can access local Docker images."
  fi
}

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

retry_curl() {
  local attempts="$1"
  shift
  local delay=2
  local err_file="/tmp/fraud-e2e-curl-error.log"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl "$@" 2>"${err_file}"; then
      return 0
    fi
    if ((attempt == attempts)); then
      cat "${err_file}" >&2
      return 1
    fi
    sleep "${delay}"
  done
}

post_transaction() {
  local tx_id="$1"
  local customer_id="$2"
  local merchant_id="$3"
  local category="$4"
  local amount="$5"
  local country="$6"
  local timestamp="$7"
  retry_curl 30 -fsS -X POST "http://127.0.0.1:${TX_LOCAL_PORT}/transactions" \
    -H 'Content-Type: application/json' \
    -d "{
      \"transaction_id\": \"${tx_id}\",
      \"customer_id\": \"${customer_id}\",
      \"card_id\": \"card-${customer_id}\",
      \"merchant_id\": \"${merchant_id}\",
      \"merchant_category\": \"${category}\",
      \"amount\": ${amount},
      \"currency\": \"EUR\",
      \"country\": \"${country}\",
      \"timestamp\": \"${timestamp}\",
      \"requested_model\": \"${MODEL}\"
    }" >/dev/null
}

label_transaction() {
  local tx_id="$1"
  local is_fraud="$2"
  local timestamp="$3"
  local reason="$4"
  retry_curl 30 -fsS -X PUT "http://127.0.0.1:${TX_LOCAL_PORT}/transactions/${tx_id}/label" \
    -H 'Content-Type: application/json' \
    -d "{
      \"is_fraud\": ${is_fraud},
      \"label_timestamp\": \"${timestamp}\",
      \"label_source\": \"e2e-demo\",
      \"label_confidence\": 1.0,
      \"annotator_id\": \"script\",
      \"reason_code\": \"${reason}\"
    }" >/dev/null
}

build_images() {
  mvn -DskipTests package
  docker build -f src/main/docker/Dockerfile.jvm -t transaction-events:local .
  docker build -f feast/Dockerfile -t fraud-feast-repo:local .
  docker build -f feast/writer/Dockerfile -t fraud-feast-writer:local .
  docker build -f kserve/java-transformer/Dockerfile -t fraud-java-transformer:local .
  docker build -f training/Dockerfile -t fraud-training:local .
  docker build -f training/mlflow/Dockerfile -t fraud-mlflow:local .

  load_image transaction-events:local
  load_image fraud-feast-repo:local
  load_image fraud-feast-writer:local
  load_image fraud-java-transformer:local
  load_image fraud-training:local
  load_image fraud-mlflow:local
}

deploy_stack() {
  if [[ "${RESET}" == "true" ]]; then
    kubectl delete namespace "${NAMESPACE}" --ignore-not-found
    kubectl create namespace "${NAMESPACE}"
  else
    kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
  fi

  helm upgrade --install "${RELEASE}" ./charts/fraud-inference-demo \
    --namespace "${NAMESPACE}" \
    -f "${VALUES_FILE}" \
    --set postgres.enabled=true \
    --set postgres.persistence.enabled=false \
    --set rustfs.enabled=true \
    --set rustfs.persistence.enabled=false \
    --set mlflow.enabled=true \
    --set featureWriter.enabled=true \
    --set kserve.transformer.implementation=java \
    --set trainingJob.enabled=false

  kubectl wait -n "${NAMESPACE}" --for=condition=complete job \
    -l app.kubernetes.io/component=rustfs-bucket-init --timeout=300s
  kubectl wait -n "${NAMESPACE}" --for=condition=Available deployment \
    -l app.kubernetes.io/instance="${RELEASE}" --timeout=300s
  kubectl wait -n "${NAMESPACE}" --for=condition=Ready inferenceservice \
    -l fraud.example.com/model="${MODEL}" --timeout=300s
}

start_transaction_port_forward() {
  local tx_svc
  tx_svc="$(svc_by_component transaction-events)"
  kubectl port-forward -n "${NAMESPACE}" "svc/${tx_svc}" "${TX_LOCAL_PORT}:8080" >/tmp/fraud-transaction-events-port-forward.log 2>&1 &
  cleanup_pids+=("$!")
  sleep 5
  retry_curl 120 -fsS "http://127.0.0.1:${TX_LOCAL_PORT}/q/openapi" >/dev/null
}

restart_transaction_port_forward() {
  cleanup
  cleanup_pids=()
  start_transaction_port_forward
}

create_labeled_training_data() {
  local base="2026-06-18T08"
  post_transaction "e2e-${RUN_ID}-train-01" "cust-a" "merchant-risk-1" "electronics" "1499.00" "US" "${base}:00:00Z"
  post_transaction "e2e-${RUN_ID}-train-02" "cust-a" "merchant-safe-1" "grocery" "32.10" "SE" "${base}:05:00Z"
  post_transaction "e2e-${RUN_ID}-train-03" "cust-b" "merchant-risk-1" "electronics" "1299.00" "NO" "${base}:10:00Z"
  post_transaction "e2e-${RUN_ID}-train-04" "cust-c" "merchant-safe-2" "fuel" "55.40" "SE" "${base}:15:00Z"
  post_transaction "e2e-${RUN_ID}-train-05" "cust-d" "merchant-risk-2" "jewelry" "2400.00" "US" "${base}:20:00Z"
  post_transaction "e2e-${RUN_ID}-train-06" "cust-c" "merchant-safe-1" "grocery" "18.25" "SE" "${base}:25:00Z"
  post_transaction "e2e-${RUN_ID}-train-07" "cust-e" "merchant-safe-3" "travel" "280.00" "DE" "${base}:30:00Z"
  post_transaction "e2e-${RUN_ID}-train-08" "cust-f" "merchant-risk-2" "jewelry" "1890.00" "US" "${base}:35:00Z"
  post_transaction "e2e-${RUN_ID}-train-09" "cust-g" "merchant-safe-4" "grocery" "44.20" "SE" "${base}:40:00Z"
  post_transaction "e2e-${RUN_ID}-train-10" "cust-h" "merchant-risk-3" "electronics" "980.00" "US" "${base}:45:00Z"

  label_transaction "e2e-${RUN_ID}-train-01" true  "${base}:30:00Z" "confirmed_high_value_cross_border"
  label_transaction "e2e-${RUN_ID}-train-02" false "${base}:35:00Z" "confirmed_legit"
  label_transaction "e2e-${RUN_ID}-train-03" true  "${base}:40:00Z" "confirmed_chargeback"
  label_transaction "e2e-${RUN_ID}-train-04" false "${base}:45:00Z" "confirmed_legit"
  label_transaction "e2e-${RUN_ID}-train-05" true  "${base}:50:00Z" "confirmed_chargeback"
  label_transaction "e2e-${RUN_ID}-train-06" false "${base}:55:00Z" "confirmed_legit"
  label_transaction "e2e-${RUN_ID}-train-07" false "${base}:56:00Z" "confirmed_legit"
  label_transaction "e2e-${RUN_ID}-train-08" true  "${base}:57:00Z" "confirmed_chargeback"
  label_transaction "e2e-${RUN_ID}-train-09" false "${base}:58:00Z" "confirmed_legit"
  label_transaction "e2e-${RUN_ID}-train-10" true  "${base}:59:00Z" "confirmed_chargeback"
}

run_training_job() {
  local train_run="train-${RUN_ID}"
  helm upgrade "${RELEASE}" ./charts/fraud-inference-demo \
    --namespace "${NAMESPACE}" \
    -f "${VALUES_FILE}" \
    --set postgres.enabled=true \
    --set rustfs.enabled=true \
    --set mlflow.enabled=true \
    --set featureWriter.enabled=true \
    --set kserve.transformer.implementation=java \
    --set trainingJob.enabled=true \
    --set-string trainingJob.runId="${train_run}" \
    --set-string trainingJob.model="${MODEL}" \
    --set-string trainingJob.registeredModelName="${REGISTERED_MODEL_NAME}" \
    --set trainingJob.minLabelAgeDays=0

  local job
  job="$(kubectl get jobs -n "${NAMESPACE}" -l app.kubernetes.io/component=training -o name | grep "${train_run}" | tail -n 1)"
  kubectl wait -n "${NAMESPACE}" --for=condition=complete "${job}" --timeout=600s
  kubectl logs -n "${NAMESPACE}" "${job}"
}

create_labeled_scoring_data() {
  local base="2026-06-18T10"
  post_transaction "e2e-${RUN_ID}-score-01" "cust-z1" "merchant-risk-1" "electronics" "1700.00" "US" "${base}:00:00Z"
  post_transaction "e2e-${RUN_ID}-score-02" "cust-z2" "merchant-safe-1" "grocery" "24.00" "SE" "${base}:05:00Z"
  post_transaction "e2e-${RUN_ID}-score-03" "cust-z3" "merchant-risk-2" "jewelry" "2100.00" "US" "${base}:10:00Z"
  post_transaction "e2e-${RUN_ID}-score-04" "cust-z4" "merchant-safe-4" "grocery" "39.90" "SE" "${base}:15:00Z"

  label_transaction "e2e-${RUN_ID}-score-01" true  "${base}:30:00Z" "confirmed_chargeback"
  label_transaction "e2e-${RUN_ID}-score-02" false "${base}:35:00Z" "confirmed_legit"
  label_transaction "e2e-${RUN_ID}-score-03" true  "${base}:40:00Z" "confirmed_chargeback"
  label_transaction "e2e-${RUN_ID}-score-04" false "${base}:45:00Z" "confirmed_legit"
}

evaluate_deployed_model() {
  local model_version="$1"
  local postgres_secret
  local postgres_svc
  postgres_secret="$(secret_by_component postgres)"
  postgres_svc="$(svc_by_component postgres)"
  kubectl delete job -n "${NAMESPACE}" "evaluate-${RUN_ID}" --ignore-not-found
  cat <<YAML | kubectl apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: evaluate-${RUN_ID}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/component: evaluation
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: evaluate
          image: fraud-training:local
          imagePullPolicy: Never
          command: ["python"]
          args:
            - training/scripts/evaluate_deployed_model.py
            - --model
            - ${MODEL}
            - --model-version
            - ${model_version}
          env:
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ${postgres_secret}
                  key: password
            - name: TRAINING_DATABASE_URL
              value: postgresql://feast:\$(POSTGRES_PASSWORD)@${postgres_svc}:5432/fraud_features
YAML
  kubectl wait -n "${NAMESPACE}" --for=condition=complete "job/evaluate-${RUN_ID}" --timeout=300s
  kubectl logs -n "${NAMESPACE}" "job/evaluate-${RUN_ID}"
}

build_images
deploy_stack
start_transaction_port_forward
create_labeled_training_data
TRAIN_LOGS="$(run_training_job)"
echo "${TRAIN_LOGS}"
MODEL_VERSION="$(printf '%s\n' "${TRAIN_LOGS}" | sed -n 's/^MLFLOW_MODEL_VERSION=//p' | tail -n 1)"
if [[ -z "${MODEL_VERSION}" ]]; then
  echo "Could not find MLFLOW_MODEL_VERSION in training logs" >&2
  exit 1
fi

MODEL_VERSION="${MODEL_VERSION}" \
MODEL="${MODEL}" \
REGISTERED_MODEL_NAME="${REGISTERED_MODEL_NAME}" \
KIND_CLUSTER="${KIND_CLUSTER}" \
MINIKUBE_PROFILE="${MINIKUBE_PROFILE}" \
scripts/simulate_model_ci.sh \
  --namespace "${NAMESPACE}" \
  --release "${RELEASE}" \
  --values-file "${VALUES_FILE}" \
  --model "${MODEL}" \
  --registered-model-name "${REGISTERED_MODEL_NAME}" \
  --model-version "${MODEL_VERSION}" \
  --image-repository fraud-model-b

restart_transaction_port_forward
create_labeled_scoring_data
evaluate_deployed_model "mlflow-${MODEL_VERSION}"

echo "E2E_DEMO_SUCCEEDED run_id=${RUN_ID} model_version=mlflow-${MODEL_VERSION}"
