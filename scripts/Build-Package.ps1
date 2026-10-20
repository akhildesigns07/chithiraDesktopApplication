# Build-Package.ps1
# Builds a ready-to-copy folder in .\dist with a bundled Java runtime, so target PCs don't need Java:
#   dist\FieryHoldSender\FieryHoldSender.exe   (no console window)
#   dist\config\config.properties
#   dist\scripts\*.ps1
# Requires JDK 21 (jpackage) and Maven on the build machine. Then on the target PC:
#   copy dist to C:\FieryHoldSender, edit config\config.properties,
#   powershell -ExecutionPolicy Bypass -File C:\FieryHoldSender\scripts\Install-Startup.ps1 -StartNow

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "dist"

Push-Location $root
try {
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

    if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
    New-Item -ItemType Directory -Path $dist | Out-Null

    $stage = Join-Path $root "target\jpackage-input"
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Copy-Item (Join-Path $root "target\fiery-hold-sender.jar") $stage -Force

    jpackage --type app-image --name FieryHoldSender --input $stage --main-jar fiery-hold-sender.jar `
        --main-class com.chithira.fieryhold.FieryHoldApp --dest $dist `
        --java-options "-Xmx256m" --app-version 1.0.0 --vendor "Chithira"
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

    Copy-Item -Recurse (Join-Path $root "config")  (Join-Path $dist "config")
    Copy-Item -Recurse (Join-Path $root "scripts") (Join-Path $dist "scripts")
    Write-Host "Package ready in $dist" -ForegroundColor Green
} finally {
    Pop-Location
}
