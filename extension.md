# Build the Browser Extension

## The extension lives in the `Komelia` submodule. Run all commands from the `Komelia` directory.

## Dont forget to change the image in the docker compose until the main developer build the new update
```bash
loloky/komf:latest
```


## Direct Download the extension and skip the building step
```bash
https://drive.google.com/file/d/18PW2WRaL_l13GlMkqwWqjWCtuRbpfq98/view?usp=sharing
```


```bash
cd Komelia
```

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