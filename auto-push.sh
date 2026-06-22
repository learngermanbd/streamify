#!/bin/bash
# auto-push.sh - Background file watcher that auto-commits and pushes changes to GitHub
# Usage: bash auto-push.sh [interval_in_seconds]
# Default interval: 30 seconds

INTERVAL=${1:-30}
REPO_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "============================================"
echo "  Auto-Push File Watcher"
echo "============================================"
echo "  Repository : $REPO_DIR"
echo "  Branch     : main"
echo "  Interval   : ${INTERVAL}s"
echo "============================================"
echo ""
echo "Watching for changes... (Press Ctrl+C to stop)"
echo ""

cd "$REPO_DIR"

while true; do
  # Check if there are any changes (staged, unstaged, or untracked)
  if ! git diff --quiet || ! git diff --cached --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]; then
    
    # Stage all changes
    git add -A
    
    # Get a summary of changed files
    CHANGED=$(git diff --cached --name-only)
    NUM_FILES=$(echo "$CHANGED" | wc -l | tr -d ' ')
    
    # Create commit message with timestamp and file count
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    COMMIT_MSG="auto: ${NUM_FILES} file(s) changed [${TIMESTAMP}]"
    
    echo "[$(date '+%H:%M:%S')] Detected ${NUM_FILES} changed file(s). Committing..."
    
    # Commit
    git commit -m "$COMMIT_MSG" --quiet
    
    # Push
    echo "[$(date '+%H:%M:%S')] Pushing to GitHub..."
    if git push origin main 2>&1; then
      echo "[$(date '+%H:%M:%S')] Successfully pushed: $COMMIT_MSG"
    else
      echo "[$(date '+%H:%M:%S')] Push failed! Will retry next cycle."
    fi
    echo ""
  fi
  
  sleep "$INTERVAL"
done
