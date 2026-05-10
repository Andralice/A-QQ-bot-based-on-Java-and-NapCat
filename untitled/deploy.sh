#!/usr/bin/env bash
# QQ Bot 自动部署脚本
# 用法：
#   chmod +x deploy.sh && ./deploy.sh
#
# 前提条件：
#   - SSH Key 已配置 (~/.ssh/id_ed25519)
#   - 服务器已配置 /opt/qq-bot/.env 环境变量文件
#
set -euo pipefail

# ======== 配置区 ========
JAR_NAME="untitled-1.0-SNAPSHOT.jar"
LOCAL_JAR_PATH="target/${JAR_NAME}"

REMOTE_USER="alice"
REMOTE_HOST="154.8.213.134"
REMOTE_DIR="/opt/qq-bot"

SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SSH_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no"

JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx768m}"
REMOTE_JAVA="${REMOTE_JAVA:-java}"

APP_NAME="qq-bot"
SCREEN_NAME="qq-bot"
REMOTE_LOG="${REMOTE_DIR}/${APP_NAME}.log"
ENV_FILE="${REMOTE_DIR}/.env"

echo "==> [1/6] Maven 打包"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"
mvn clean package -DskipTests

if [[ ! -f "${LOCAL_JAR_PATH}" ]]; then
  echo "❌ 未找到打包产物: ${SCRIPT_DIR}/${LOCAL_JAR_PATH}"
  exit 1
fi

echo "==> [2/6] 确保远程目录存在"
ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" "mkdir -p ${REMOTE_DIR}"

echo "==> [3/6] 上传 JAR"
scp ${SSH_OPTS} "${LOCAL_JAR_PATH}" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/${JAR_NAME}.new"

echo "==> [4/6] 停止运行中的实例"
ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" bash -s << 'EOS'
set -euo pipefail
REMOTE_DIR="/opt/qq-bot"
SCREEN_NAME="qq-bot"
JAR_NAME="untitled-1.0-SNAPSHOT.jar"
cd "${REMOTE_DIR}"

# 停止现有的 screen 会话
if screen -list | grep -q "${SCREEN_NAME}"; then
  echo "🛑 停止现有的 ${SCREEN_NAME} screen 会话..."
  screen -S "${SCREEN_NAME}" -X quit
  sleep 2
fi

# 清理可能的残留进程
while IFS= read -r pid; do
  [[ "${pid}" =~ ^[0-9]+$ ]] && kill "${pid}" 2>/dev/null || true
done < <(pgrep -f "${JAR_NAME}" || true)
sleep 2
EOS

echo "==> [5/6] 服务器替换 JAR（带备份）"
ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" bash -s << 'EOS'
set -euo pipefail
REMOTE_DIR="/opt/qq-bot"
JAR_NAME="untitled-1.0-SNAPSHOT.jar"
cd "${REMOTE_DIR}"
if [[ -f "${JAR_NAME}" ]]; then
  cp "${JAR_NAME}" "${JAR_NAME}.bak.$(date +%Y%m%d_%H%M%S)"
fi
mv "${JAR_NAME}.new" "${JAR_NAME}"
EOS

echo "==> [6/6] 启动服务"
ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" bash -s << 'EOS'
set -euo pipefail
REMOTE_DIR="/opt/qq-bot"
JAR_NAME="untitled-1.0-SNAPSHOT.jar"
SCREEN_NAME="qq-bot"
REMOTE_LOG="${REMOTE_DIR}/qq-bot.log"
ENV_FILE="${REMOTE_DIR}/.env"

cd "${REMOTE_DIR}"

# 加载环境变量
if [[ -f "${ENV_FILE}" ]]; then
  echo "📋 加载环境变量: ${ENV_FILE}"
  source "${ENV_FILE}"
else
  echo "⚠️  未找到 .env 文件: ${ENV_FILE}"
fi

JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx768m}"

# 启动新的 screen 会话
echo "▶️  启动 QQ Bot..."
screen -S "${SCREEN_NAME}" -d -m bash -c '
  source /opt/qq-bot/.env
  java ${JAVA_OPTS} -jar /opt/qq-bot/'"${JAR_NAME}"' > /opt/qq-bot/qq-bot.log 2>&1
'

sleep 3

# 检查是否启动成功
if screen -list | grep -q "${SCREEN_NAME}"; then
  echo "✅ QQ Bot 已成功启动在 screen '${SCREEN_NAME}' 中"
  echo "📋 管理命令:"
  echo "  查看运行状态: screen -list"
  echo "  进入 screen:   screen -r ${SCREEN_NAME}"
  echo "  停止服务:     screen -S ${SCREEN_NAME} -X quit"
  echo "  查看日志:     tail -f ${REMOTE_LOG}"
else
  echo "❌ 启动失败，请检查日志"
  tail -40 "${REMOTE_LOG}" || true
  exit 1
fi
EOS

echo ""
echo "✅ 发布完成"
echo "查看日志: ssh ${SSH_OPTS} ${REMOTE_USER}@${REMOTE_HOST} \"tail -n 100 ${REMOTE_LOG}\""
echo "查看进程: ssh ${SSH_OPTS} ${REMOTE_USER}@${REMOTE_HOST} \"screen -list\""
