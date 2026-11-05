param(
    [string]$Tag = "",
    [switch]$Last,
    [switch]$List,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$root = (git rev-parse --show-toplevel).Trim()
if (-not $root) {
    throw "Не найден Git-репозиторий."
}
Set-Location $root

function Get-Snapshots {
    git for-each-ref --sort=-creatordate --format="%(refname:short)|%(objectname)|%(creatordate:iso8601)" refs/tags/snap-*
}

if ($List -or (-not $Tag -and -not $Last)) {
    $head = (git rev-parse HEAD).Trim()
    Write-Output "Локальные снимки (новые сверху):"
    foreach ($line in Get-Snapshots) {
        $parts = $line -split "\|", 3
        if ($parts.Count -lt 3) { continue }
        $name = $parts[0]
        $commit = $parts[1]
        $date = $parts[2]
        $mark = if ($commit -eq $head) { "  <-- HEAD" } else { "" }
        Write-Output ("  {0}  {1}{2}" -f $date, $name, $mark)
    }
    Write-Output ""
    Write-Output "Откат: .\tools\rollback.ps1 -Last [-Force]"
    Write-Output "Или:   .\tools\rollback.ps1 -Tag <имя> [-Force]"
    return
}

$head = (git rev-parse HEAD).Trim()
$target = $null

if ($Tag) {
    if (-not (git rev-parse -q --verify "refs/tags/$Tag")) {
        throw "Снимок не найден: $Tag"
    }
    $target = $Tag
} else {
    foreach ($line in Get-Snapshots) {
        $parts = $line -split "\|", 3
        if ($parts.Count -lt 3) { continue }
        if ($parts[1] -ne $head) {
            $target = $parts[0]
            break
        }
    }
    if (-not $target) {
        throw "Предыдущий снимок не найден. Используй -Tag."
    }
}

$targetCommit = (git rev-parse "$target^{commit}").Trim()
if ($targetCommit -eq $head) {
    Write-Output "Текущий HEAD уже соответствует $target. Откат не нужен."
    return
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$safetyBranch = "safety/$stamp"

if (-not $Force) {
    Write-Output "Будет выполнен откат:"
    Write-Output "  с:   $head"
    Write-Output "  на:   $target ($targetCommit)"
    $answer = Read-Host "Продолжить? [y/N]"
    if ($answer -notmatch "^[yYдД]$") {
        Write-Output "Отменено."
        return
    }
}

# Страховка: текущее состояние сохраняется и коммитом, и веткой.
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    git commit -m "safety: before rollback $stamp" | Out-Null
}
git branch $safetyBranch | Out-Null

git reset --hard $targetCommit | Out-Null

Write-Output "Откат выполнен."
Write-Output "  сейчас: $(git rev-parse --short HEAD) ($target)"
Write-Output "  страховка: ветка $safetyBranch"
Write-Output "Вернуться обратно:"
Write-Output "  git reset --hard $safetyBranch"
Write-Output "GitHub: не затрагивался"
