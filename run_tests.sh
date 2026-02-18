#!/bin/bash
# Test script for workshop server

set -e

echo "Starting workshop server in background..."
PORT=4243 WORKSHOP_VERBOSE=true bb workshop.bb &
SERVER_PID=$!

# Wait for server to start
sleep 2

# Check if server is running
if ! curl -s http://localhost:4243/status > /dev/null; then
    echo "ERROR: Server failed to start"
    kill $SERVER_PID 2>/dev/null || true
    exit 1
fi

echo "Server is running. Running tests..."

# Test 1: Task creation with :from
echo "Test 1: Task creation with :from"
curl -s -X POST http://localhost:4243/tasks \
    -H 'Content-Type: application/json' \
    -d '{"from":"test.me","title":"task with from","context":{}}' | jq .

# Test 2: Task creation with :created_by
echo -e "\nTest 2: Task creation with :created_by"
curl -s -X POST http://localhost:4243/tasks \
    -H 'Content-Type: application/json' \
    -d '{"created_by":"test.me","title":"task with created_by","context":{}}' | jq .

# Test 3: Task creation without either (should fail)
echo -e "\nTest 3: Task creation without from/created_by (should fail)"
curl -s -X POST http://localhost:4243/tasks \
    -H 'Content-Type: application/json' \
    -d '{"title":"bad task","context":{}}' | jq .

# Test 4: SSE connection test
echo -e "\nTest 4: SSE connection"
timeout 5 bash -c 'curl -sN http://localhost:4243/ch/test &
sleep 1
curl -s -X POST http://localhost:4243/ch/test \
    -H "Content-Type: application/json" \
    -d "{\"from\":\"test\",\"type\":\"test\",\"body\":{}}"
sleep 2' || true

echo -e "\nTests complete!"

# Cleanup
kill $SERVER_PID 2>/dev/null || true
