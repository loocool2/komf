# Build & Deployment Guide

This guide covers building the Komf backend Docker image, pushing it to Docker Hub, building the browser extension, and loading it in Chrome or Brave.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 17 | Required only to build the JAR before Docker build |
| Docker | Latest | With BuildKit enabled for multi-arch builds |
| Git | Any | With submodules initialized |

Set `JAVA_HOME` to your JDK 17 installation before running any Gradle commands.

**Windows example:**
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
```

Make sure submodules are initialized:
```bash
git submodule update --init --recursive
```

---

## 1. Build the Komelia Web UI

The Komf server serves a full web UI at `http://localhost:8085` built from the `komelia-app` wasmJs module.
It must be compiled before the JAR so the Gradle `processResources` task can embed it.

**Linux / macOS:**
```bash
cd Komelia
./gradlew :komelia-app:wasmJsBrowserDistribution :komelia-image-decoder:wasm-image-worker:wasmJsBrowserDistribution
cd ..
```

**Windows:**
```powershell
Set-Location Komelia
.\gradlew.bat :komelia-app:wasmJsBrowserDistribution :komelia-image-decoder:wasm-image-worker:wasmJsBrowserDistribution
Set-Location ..
```

Output:
- `Komelia/komelia-app/build/dist/wasmJs/productionExecutable/` — main app
- `Komelia/komelia-image-decoder/wasm-image-worker/build/dist/wasmJs/productionExecutable/` — `komeliaImageWorker.js` (required by the image decoder web worker)

The main Gradle build will automatically copy these files into `komf-app/src/main/resources/komelia/`
when you run the next step.

---

## 2. Build the Komf Backend JAR

The Dockerfile expects the fat JAR to be present before building the image.

**Linux / macOS:**
```bash
./gradlew :komf-app:shadowJar
```

**Windows:**
```powershell
.\gradlew.bat :komf-app:shadowJar
```

Output: `komf-app/build/libs/komf-app-1.0.0-SNAPSHOT-all.jar`

> The web UI files are automatically embedded in the JAR via the `copyKomeliaWebApp` Gradle task,
> which runs as part of `processResources`. They will be served at `http://<host>:8085/`.

---

## 2b. Quick Full Build (Windows — recommended)

A PowerShell script automates steps 1 through 4 in one command:

```powershell
# Build, create image, and push to Docker Hub
.\build-docker.ps1

# Build with a specific version tag (also tags :latest)
.\build-docker.ps1 -Tag "0.9.2"

# Build image only, skip push
.\build-docker.ps1 -NoPush
```

---

## 3. Build & Push the Docker Image

### Login to Docker Hub
```bash
docker login
```

### Single-arch build (amd64 only — simplest)
```bash
docker build -t loloky/komf:latest .
docker push loloky/komf:latest
```

To also tag a specific version:
```bash
docker tag loloky/komf:latest loloky/komf:0.9.x
docker push loloky/komf:0.9.x
```

### Multi-arch build (amd64 + arm64 + arm — recommended for Docker Hub)

This requires Docker Buildx (included in Docker Desktop):

```bash
docker buildx create --use --name multiarch-builder
docker buildx build \
  --platform linux/amd64,linux/arm64,linux/arm/v7 \
  -t loloky/komf:latest \
  --push \
  .
```

> The Dockerfile already defines separate base images per architecture so this works out of the box.

---

## 3. Run with Docker

### Docker Compose
```yml
version: "3.7"
services:
  komf:
    image: loloky/komf:latest
    container_name: komf
    ports:
      - "8085:8085"
    user: "1000:1000"
    environment:
      - KOMF_KAVITA_BASE_URI=http://kavita:5000
      - KOMF_KAVITA_API_KEY=your-kavita-api-key
      - KOMF_LOG_LEVEL=INFO
    volumes:
      - /path/to/config:/config
    restart: unless-stopped
```

### Docker CLI
```bash
docker create \
  --name komf \
  -p 8085:8085 \
  -u 1000:1000 \
  -e KOMF_KAVITA_BASE_URI=http://kavita:5000 \
  -e KOMF_KAVITA_API_KEY=your-kavita-api-key \
  -e KOMF_LOG_LEVEL=INFO \
  -v /path/to/config:/config \
  --restart unless-stopped \
  loloky/komf:latest

docker start komf
```

### Networking (if Kavita runs in another container)
```bash
docker network create manga-network
docker network connect manga-network kavita
docker network connect manga-network komf
```

---

## 4. Build the Browser Extension

The extension lives in the `Komelia` submodule. Run all commands from the `Komelia` directory.

```bash
cd Komelia
```

### Development build (faster, larger files)

**Linux / macOS:**
```bash
./gradlew :komelia-komf-extension:app:assembleExtensionDev
```

**Windows:**
```powershell
.\gradlew.bat :komelia-komf-extension:app:assembleExtensionDev
```

Output folder: `komelia-komf-extension/app/build/extensionDev/`

### Production build (optimized, smaller files)

```bash
# Linux / macOS
./gradlew :komelia-komf-extension:app:assembleExtension

# Windows
.\gradlew.bat :komelia-komf-extension:app:assembleExtension
```

Output folder: `komelia-komf-extension/app/build/extension/`

---

## 5. Load the Extension in Chrome or Brave

1. Open your browser and navigate to:
   - **Chrome:** `chrome://extensions`
   - **Brave:** `brave://extensions`

2. Enable **Developer mode** using the toggle in the top-right corner.

3. Click **Load unpacked**.

4. Select the output folder from the build step:
   - Dev build: `Komelia/komelia-komf-extension/app/build/extensionDev`
   - Production build: `Komelia/komelia-komf-extension/app/build/extension`

5. The Komf extension will appear in your extensions list. Pin it to the toolbar if needed.

6. Click the extension icon, enter your Komf server URL (e.g. `http://localhost:8085`), and grant permission for your Kavita origin.

7. Refresh your Kavita tab — the puzzle piece icon will appear in the navbar.

### Updating the extension after a code change

1. Rebuild using the command from step 4.
2. Go back to `chrome://extensions` or `brave://extensions`.
3. Click the **reload icon** on the Komf extension card.
4. Hard-refresh your Kavita tab (`Ctrl+Shift+R`).

---

## Quick Reference

| Task | Command |
|------|---------|
| Full build + push (Windows) | `.\build-docker.ps1` |
| Full build + push with tag | `.\build-docker.ps1 -Tag "0.9.2"` |
| Build web UI | `cd Komelia && .\gradlew.bat :komelia-app:wasmJsBrowserDistribution` |
| Build JAR (embeds web UI) | `.\gradlew.bat :komf-app:shadowJar` |
| Build Docker image | `docker build -t loloky/komf:latest .` |
| Push to Docker Hub | `docker push loloky/komf:latest` |
| Build extension (dev) | `cd Komelia && .\gradlew.bat :komelia-komf-extension:app:assembleExtensionDev` |
| Build extension (prod) | `cd Komelia && .\gradlew.bat :komelia-komf-extension:app:assembleExtension` |
