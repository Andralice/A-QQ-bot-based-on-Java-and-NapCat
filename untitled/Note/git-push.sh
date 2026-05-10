#!/usr/bin/env bash
# GitHub 自动推送脚本
# 用法：
#   ./Note/git-push.sh "commit message"
#   ./Note/git-push.sh          # 使用默认 commit message
#
# 安全：自动排除 Note/.env*、application.properties 等敏感文件
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

cd "${REPO_DIR}"

# ======== 提交信息 ========
COMMIT_MSG="${1:-chore: auto update $(date '+%Y-%m-%d %H:%M')}"
BRANCH="${2:-main}"

echo "========================================="
echo "  GitHub 自动推送"
echo "  分支: ${BRANCH}"
echo "  提交: ${COMMIT_MSG}"
echo "========================================="

# ======== 检查工作区 ========
if [[ -z "$(git status --porcelain)" ]]; then
  echo "✅ 工作区干净，无需推送"
  exit 0
fi

echo ""
echo "📋 当前变更:"
git status --short
echo ""

# ======== 安全检查 ========
STAGED_FILES=$(git status --porcelain | cut -c4-)
SENSITIVE_FILES=""
for f in $STAGED_FILES; do
  case "$f" in
    *application.properties*|Note/.env*|Note/env-config|*.pem|*.key|*credentials*|*.env)
      SENSITIVE_FILES="$SENSITIVE_FILES $f"
      ;;
  esac
done

if [[ -n "$SENSITIVE_FILES" ]]; then
  echo "⚠️  检测到以下敏感文件在变更中:"
  for f in $SENSITIVE_FILES; do
    echo "    $f"
  done
  echo ""

  read -rp "这些文件不会提交。是否继续？(y/n) " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "❌ 已取消"
    exit 1
  fi
fi

# ======== 添加文件（排除敏感文件） ========
echo "📦 添加文件..."
git add -A

# 强制取消敏感文件的暂存
for pattern in \
  "application.properties" \
  "Note/.env*" \
  "Note/env-config" \
  "*.pem" \
  "*.key" \
  "*credentials*" \
  "*.env"; do
  git reset -- "$pattern" 2>/dev/null || true
done

# ======== 提交 ========
echo "📝 提交: ${COMMIT_MSG}"
git commit -m "${COMMIT_MSG}"

# ======== 推送 ========
echo "🚀 推送到 origin/${BRANCH}..."
git push origin "${BRANCH}"

echo ""
echo "✅ 推送完成"
echo "========================================="
