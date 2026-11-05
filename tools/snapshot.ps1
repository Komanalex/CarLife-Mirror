param(
    [string]$Label = "snapshot"
)

$ErrorActionPreference = "Stop"

$root = (git rev-parse --show-toplevel).Trim()
if (-not $root) {
    throw "Не найден Git-репозиторий."
}
Set-Location $root

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$slug = ($Label.ToLowerInvariant() -replace "[^a-z0-9]+", "-").Trim("-")
if (-not $slug) { $slug = "snapshot" }

git add -A
git diff --cached --quiet
$hasChanges = $LASTEXITCODE -ne 0

if ($hasChanges) {
    git commit -m "checkpoint: $Label ($stamp)" | Out-Null
}

$tag = "snap-$stamp-$slug"
$suffix = 1
while (git rev-parse -q --verify "refs/tags/$tag") {
    $suffix++
    $tag = "snap-$stamp-$slug-$suffix"
}

git tag -a $tag -m "Local checkpoint: $Label"

$commit = (git rev-parse --short HEAD).Trim()
Write-Output "Snapshot: $tag"
Write-Output "Commit:   $commit"
if (-not $hasChanges) {
    Write-Output "Changes:  нет (тег поставлен на текущий HEAD)"
}
Write-Output "GitHub:   не отправлялось"
