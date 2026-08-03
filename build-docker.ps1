# Full build pipeline: Komelia web UI → Komf JAR → Docker image → Docker Hub push
# Usage: .\build-docker.ps1 [-Tag "0.9.x"] [-NoPush]

param(
    [string]$Tag = "latest",
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

$ImageName = "loloky/komf"

Write-Host "==> Step 1: Build Komelia web app and image worker (wasmJs)" -ForegroundColor Cyan
Set-Location "$PSScriptRoot\Komelia"
.\gradlew.bat :komelia-app:wasmJsBrowserDistribution :komelia-image-decoder:wasm-image-worker:wasmJsBrowserDistribution
if ($LASTEXITCODE -ne 0) { throw "Komelia web app build failed" }

Write-Host "==> Step 2: Build Komf backend JAR (includes web UI)" -ForegroundColor Cyan
Set-Location "$PSScriptRoot"
.\gradlew.bat :komf-app:clean :komf-app:shadowJar
if ($LASTEXITCODE -ne 0) { throw "Komf JAR build failed" }

Write-Host "==> Step 3: Build Docker image" -ForegroundColor Cyan
docker build -t "${ImageName}:${Tag}" .
if ($LASTEXITCODE -ne 0) { throw "Docker build failed" }

if ($Tag -ne "latest") {
    docker tag "${ImageName}:${Tag}" "${ImageName}:latest"
}

if (-not $NoPush) {
    Write-Host "==> Step 4: Push to Docker Hub" -ForegroundColor Cyan
    docker push "${ImageName}:${Tag}"
    if ($Tag -ne "latest") {
        docker push "${ImageName}:latest"
    }
    Write-Host "Done. Image available at: ${ImageName}:${Tag}" -ForegroundColor Green
} else {
    Write-Host "Done. Skipped push. Run: docker push ${ImageName}:${Tag}" -ForegroundColor Yellow
}
