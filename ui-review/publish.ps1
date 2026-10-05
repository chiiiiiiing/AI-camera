param(
    [string]$TargetRepo = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'genwow-ui-review')
)

$ErrorActionPreference = 'Stop'
$target = (Resolve-Path -LiteralPath $TargetRepo).Path
if (-not (Test-Path -LiteralPath (Join-Path $target '.git'))) {
    throw "目标不是 Git 仓库: $target"
}
$remote = (& git -C $target remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $remote -notin @(
    'https://github.com/pengjinrui278/genwow-ui-review.git',
    'git@github.com:pengjinrui278/genwow-ui-review.git'
)) {
    throw "目标仓库地址不符: $remote"
}
$changes = & git -C $target status --porcelain
if ($LASTEXITCODE -ne 0 -or $changes) {
    throw '目标仓库有未提交更改；为避免覆盖，请先处理这些更改。'
}
& git -C $target pull --ff-only origin main
if ($LASTEXITCODE -ne 0) { throw '目标仓库无法快进到最新 main；未覆盖任何预览文件。' }

& node (Join-Path $PSScriptRoot 'export.mjs')
if ($LASTEXITCODE -ne 0) { throw '预览导出失败。' }
$output = Join-Path $PSScriptRoot 'dist'
Get-ChildItem -LiteralPath $output -Force | Copy-Item -Destination $target -Recurse -Force

& git -C $target add -- .
if ($LASTEXITCODE -ne 0) { throw '暂存预览文件失败。' }
& git -C $target diff --cached --quiet
if ($LASTEXITCODE -eq 0) {
    Write-Output '预览内容没有变化，无需推送。'
    exit 0
}
& git -C $target commit -m 'chore: refresh GenWow UI preview'
if ($LASTEXITCODE -ne 0) { throw '提交预览失败。' }
& git -C $target push origin main
if ($LASTEXITCODE -ne 0) { throw '推送预览失败。' }
Write-Output '预览已更新到 genwow-ui-review/main。'
