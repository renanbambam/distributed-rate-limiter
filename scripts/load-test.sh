#!/bin/bash
# Sends 15 requests with the same client id so you can watch the configured
# algorithm start throttling once its limit is hit (default limit is 10).
set -euo pipefail

URL="${1:-http://localhost:8080/api/demo}"
CLIENT_ID="load-test-client"
HEADERS_FILE=$(mktemp)

for i in $(seq 1 15); do
  status=$(curl -s -o /dev/null -D "$HEADERS_FILE" -w "%{http_code}" \
    -H "X-Client-Id: $CLIENT_ID" "$URL")

  remaining=$(grep -i "X-RateLimit-Remaining" "$HEADERS_FILE" | tr -d '\r' | awk '{print $2}')
  algorithm=$(grep -i "X-RateLimit-Algorithm" "$HEADERS_FILE" | tr -d '\r' | awk '{print $2}')

  echo "req $i -> HTTP $status | remaining: $remaining | algorithm: $algorithm"
done

rm -f "$HEADERS_FILE"
