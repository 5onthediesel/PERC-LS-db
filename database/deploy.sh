#!/bin/bash
set -e

# =============================================================
# CS370 Wildlife Photo Upload — Backend Fix & Redeploy Script
# =============================================================
#
# WHAT THIS FIXES:
#   1. server.port now uses ${PORT:8080} so Cloud Run can inject its port
#   2. CORS allows https://cs370-perc.web.app and .firebaseapp.com
#   3. db.java connect() is lazy — no startup crash if DB is unreachable
#
# PREREQUISITES:
#   - gcloud CLI authenticated
#   - Docker running locally (or use Cloud Build — see option B below)
#   - You're in the project root with the fixed source files
#
# =============================================================

PROJECT_ID="cs370-perc"
REGION="us-central1"
REPO="java-backend"
IMAGE="java-backend"
SERVICE="java-backend"
FULL_IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}/${IMAGE}"

echo "=== Step 1: Configure Docker for Artifact Registry ==="
gcloud auth configure-docker ${REGION}-docker.pkg.dev --quiet

echo ""
echo "=== Step 2: Build the Docker image ==="
docker build -t ${FULL_IMAGE}:latest .

echo ""
echo "=== Step 3: Push to Artifact Registry ==="
docker push ${FULL_IMAGE}:latest

echo ""
echo "=== Step 4: Deploy to Cloud Run ==="
gcloud run deploy ${SERVICE} \
  --image "${FULL_IMAGE}:latest" \
  --platform managed \
  --region ${REGION} \
  --allow-unauthenticated \
  --add-cloudsql-instances perc-490419:us-east1:perc \
  --set-secrets=/app/cloud-sql-key.json=cloud-sql-key:latest \
  --set-env-vars GOOGLE_APPLICATION_CREDENTIALS=/app/cloud-sql-key.json

echo ""
echo "=== Step 5: Verify deployment ==="
SERVICE_URL=$(gcloud run services describe ${SERVICE} --region ${REGION} --format='value(status.url)')
echo "Service URL: ${SERVICE_URL}"
echo ""
echo "Testing health (expect 404 or a valid response, NOT a connection error):"
curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" "${SERVICE_URL}/"

echo ""
echo "=== Done! ==="
echo ""
echo "If the backend is up, now redeploy the React frontend:"
echo "  cd <your-react-project>"
echo "  npm run build"
echo "  firebase deploy"

# =============================================================
# OPTION B: If you don't have Docker locally, use Cloud Build:
#
#   gcloud builds submit --tag ${FULL_IMAGE}:latest .
#
#   Then run Step 4 (gcloud run deploy ...) as above.
# =============================================================