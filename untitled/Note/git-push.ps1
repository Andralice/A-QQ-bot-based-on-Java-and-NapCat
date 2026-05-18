# GitHub 推送脚本 (PowerShell / VS Code 可用)
# 用法: .\Note\git-push.ps1 "commit message"
param(
    [string]$Message = "chore: auto update $(Get-Date -Format 'yyyy-MM-dd HH:mm')",
    [string]$Branch = "main"
)

Set-Location $PSScriptRoot\..

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  GitHub 推送: $Branch / $Message" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

$changes = git status --porcelain
if (-not $changes) {
    Write-Host "✅ 工作区干净" -ForegroundColor Green
    exit 0
}

Write-Host "📋 变更:" -ForegroundColor Yellow
git status --short

# 添加并提交
git add -A

# 排除敏感文件
$sensitive = @("application.properties", "Note\.env*", "Note\env-config", "*.pem", "*.key", "*credentials*", "*.env")
foreach ($p in $sensitive) {
    git reset -- $p 2>$null
}

Write-Host "📝 提交: $Message" -ForegroundColor Yellow
git commit -m $Message

Write-Host "🚀 推送..." -ForegroundColor Yellow
git push origin $Branch

Write-Host "✅ 推送完成" -ForegroundColor Green
