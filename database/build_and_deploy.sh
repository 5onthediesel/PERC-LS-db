#!/usr/bin/env bash
set -euo pipefail

# Cloud Run deployment script using production secrets with Cloud Run mount paths (/secrets/)
# For local testing with relative paths (./secrets/), use ./local_testing.sh instead

PROJECT_ID="cs370-perc"
REGION="us-central1"
SERVICE_NAME="java-backend"
IMAGE_URI="${REGION}-docker.pkg.dev/${PROJECT_ID}/java-backend/java-backend:latest"
CLOUD_SQL_INSTANCE="cs370perc:us-east1:cs370-perc-sql"

# Local files in your repo that should never be committed.
APP_SECRETS_FILE="./secrets/app-secrets.json"
SERVICE_ACCOUNT_FILE="./secrets/service-account.json"
GMAIL_CREDENTIALS_FILE="./secrets/gmail-credentials.json"
GMAIL_TOKEN_FILE="./secrets/gmail-token/StoredCredential"

# Secret Manager secret names.
APP_SECRETS_SECRET_NAME="app-secrets-json"
SERVICE_ACCOUNT_SECRET_NAME="gcs-service-account-json"
GMAIL_CREDENTIALS_SECRET_NAME="gmail-credentials-json"
GMAIL_TOKEN_SECRET_NAME="gmail-token"

if [[ ! -f "${APP_SECRETS_FILE}" ]]; then
  echo "Missing ${APP_SECRETS_FILE}"
  exit 1
fi

if [[ ! -f "${SERVICE_ACCOUNT_FILE}" ]]; then
  echo "Missing ${SERVICE_ACCOUNT_FILE}"
  exit 1
fi

if [[ ! -f "${GMAIL_CREDENTIALS_FILE}" ]]; then
  echo "Missing ${GMAIL_CREDENTIALS_FILE}"
  exit 1
fi

if [[ ! -f "${GMAIL_TOKEN_FILE}" ]]; then
  echo "Missing ${GMAIL_TOKEN_FILE}"
  exit 1
fi

# Safeguard: prevent accidental upload of local testing secrets
if [[ -f "./secrets/local_testing_secrets.json" ]]; then
  echo "WARNING: local_testing_secrets.json should NOT be deployed to production!"
  echo "This file is for local development only and uses relative paths (./secrets/)."
  echo "Proceeding with production deploy using app-secrets.json with Cloud Run paths (/secrets/)."
  read -p "Continue deployment? (y/n) " -n 1 -r
  echo
  if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Deployment cancelled."
    exit 1
  fi
fi

gcloud config set project "${PROJECT_ID}" >/dev/null

ensure_secret_exists() {
  local secret_name="$1"
  if ! gcloud secrets describe "${secret_name}" >/dev/null 2>&1; then
    gcloud secrets create "${secret_name}" --replication-policy="automatic"
  fi
}

ensure_secret_exists "${APP_SECRETS_SECRET_NAME}"
ensure_secret_exists "${SERVICE_ACCOUNT_SECRET_NAME}"
ensure_secret_exists "${GMAIL_CREDENTIALS_SECRET_NAME}"
ensure_secret_exists "${GMAIL_TOKEN_SECRET_NAME}"

# Add new secret versions from local files.
gcloud secrets versions add "${APP_SECRETS_SECRET_NAME}" --data-file="${APP_SECRETS_FILE}"
gcloud secrets versions add "${SERVICE_ACCOUNT_SECRET_NAME}" --data-file="${SERVICE_ACCOUNT_FILE}"
gcloud secrets versions add "${GMAIL_CREDENTIALS_SECRET_NAME}" --data-file="${GMAIL_CREDENTIALS_FILE}"
gcloud secrets versions add "${GMAIL_TOKEN_SECRET_NAME}" --data-file="${GMAIL_TOKEN_FILE}"

# Build and push image.
gcloud builds submit --tag "${IMAGE_URI}" .

# Deploy Cloud Run and mount secrets as files in separate directories.
gcloud run deploy "${SERVICE_NAME}" \
  --image "${IMAGE_URI}" \
  --platform managed \
  --region "${REGION}" \
  --allow-unauthenticated \
  --add-cloudsql-instances "${CLOUD_SQL_INSTANCE}" \
  --set-secrets=/secrets/app-secrets/app-secrets.json="${APP_SECRETS_SECRET_NAME}":latest,/secrets/service-account/service-account.json="${SERVICE_ACCOUNT_SECRET_NAME}":latest,/secrets/gmail-credentials/gmail-credentials.json="${GMAIL_CREDENTIALS_SECRET_NAME}":latest,/secrets/gmail-token/StoredCredential="${GMAIL_TOKEN_SECRET_NAME}":latest \
  --set-env-vars APP_SECRETS_PATH=/secrets/app-secrets/app-secrets.json