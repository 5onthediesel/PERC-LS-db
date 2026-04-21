#!/usr/bin/env bash
set -euo pipefail

# Local testing script with relative secret paths
export APP_SECRETS_PATH="./secrets/local_testing_secrets.json"
export POLL_EMAIL_ON_STARTUP=true

# Verify local secrets file exists
if [[ ! -f "${APP_SECRETS_PATH}" ]]; then
  echo "Error: ${APP_SECRETS_PATH} not found"
  echo "Make sure you have ./secrets/local_testing_secrets.json with relative paths"
  exit 1
fi

echo "Using local secrets from: ${APP_SECRETS_PATH}"
mvn spring-boot:run