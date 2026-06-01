# QQ Bot 部署脚本 (PowerShell)
# 用法: .\Note\deploy.ps1
$ErrorActionPreference = "Continue"

$SSH_KEY = "$env:USERPROFILE\.ssh\id_ed25519"
$SERVER = "alice@154.8.213.134"
$REMOTE_DIR = "/opt/qq-bot"
$JAR_NAME = "untitled-1.0-SNAPSHOT.jar"

function ssh-exec {
    param([string]$cmd)
    $arg = "-i `"$SSH_KEY`" -o StrictHostKeyChecking=no $SERVER `"$cmd`""
    $result = cmd /c "ssh $arg 2>&1"
    return $result
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  QQ Bot 部署" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Build
Write-Host "[1/5] Maven 构建..." -ForegroundColor Yellow
Set-Location "$PSScriptRoot\.."
mvn clean package -DskipTests
if (-not (Test-Path "target/$JAR_NAME")) {
    Write-Host "构建失败!" -ForegroundColor Red
    exit 1
}
Write-Host "构建完成" -ForegroundColor Green

# 2. Upload JAR
Write-Host "[2/5] 上传 JAR..." -ForegroundColor Yellow
scp -i $SSH_KEY -o StrictHostKeyChecking=no "target/$JAR_NAME" "${SERVER}:${REMOTE_DIR}/${JAR_NAME}"
Write-Host "上传完成" -ForegroundColor Green

# 3. Stop old process
Write-Host "[3/5] 停止旧进程..." -ForegroundColor Yellow
ssh-exec "pkill -f 'untitled-1.0' 2>/dev/null; sleep 2; echo stopped"
Write-Host "已停止" -ForegroundColor Green

# 4. Start bot
Write-Host "[4/5] 启动机器人..." -ForegroundColor Yellow
ssh-exec "source $REMOTE_DIR/.env; screen -dmS qq-bot bash -c 'source $REMOTE_DIR/.env; java -Xms256m -Xmx768m -jar $REMOTE_DIR/$JAR_NAME > $REMOTE_DIR/qq-bot.log 2>&1'; sleep 10; screen -list | grep qq-bot && echo 'Bot OK' || (echo 'Bot FAILED'; tail -20 $REMOTE_DIR/qq-bot.log)"

# 5. Ensure web server
Write-Host "[5/5] 确保网站运行..." -ForegroundColor Yellow
ssh-exec "screen -list | grep -q qqbot-web || (cd $REMOTE_DIR/docs; screen -dmS qqbot-web bash -c 'python3 -m http.server 3000 --bind 0.0.0.0'; echo 'Web started') && echo 'Web OK'"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  部署完成" -ForegroundColor Green
Write-Host "  网站: http://154.8.213.134:3000" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
