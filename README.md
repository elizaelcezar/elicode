# EliCode

**Your complete development environment on Android. No computer. No Termux. No external ADB.**

EliCode turns an ARM64 Android phone into a self-contained IDE:

- 📁 Create / open / import / clone projects (Android, Node, Vite, Python, static web…)
- ✏️ Code editor with syntax highlight, find/replace, undo/redo, go-to-line
- 🐧 **Real Linux terminal** — Ubuntu ARM64 via integrated PRoot (no Termux app)
- 🤖 **OpenCode AI agent** running inside the project (`opencode run`, `opencode web`)
- 🐙 GitHub: token auth (Keystore), list/search repos, clone, pull/push
- 🔍 Localhost preview (WebView) for dev servers
- 📦 Gradle builds inside the runtime → **APK/AAB → install on the same device**

## Requirements

- Android 9+ (API 28), **ARM64 (arm64-v8a)** ou **emulador x86_64**
- ~1GB free storage for the runtime (download ~30MB, installed ~150MB base)

## Build (this repo)

```sh
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Toolchain (preinstalled on the build machine): JDK 17, Android SDK 36,
ARM64 Build Tools 35.0.0, Gradle 8.14.3, AGP 8.11.0, Kotlin 1.9.22.

Run unit tests:

```sh
gradle :app:testDebugUnitTest
```

## First run (on device)

1. Open EliCode → **Prepare environment** (downloads Ubuntu 22.04 ARM64 +
   PRoot 5.1.107, verifies SHA-256, extracts, smoke-tests bash).
2. **Start coding** → create an Android project from template.
3. Open Terminal (`java -version`, `node --version`), ask the AI to edit,
   press **Build APK** → **Install**.

## Architecture

```
Android Host (Kotlin + Compose + Material3)
  ├─ UI: Projects / Editor / Terminal / AI / Preview / Build / Git / GitHub / Settings
  ├─ RuntimeManager: install · validate · repair · host/guest exec · process registry
  ├─ Linux runtime (app-private): Ubuntu ARM64 + PRoot + tools
  ├─ Engines: GitEngine · OpenCodeEngine · GradleBuildEngine · PreviewEngine · GitHubApi
  └─ EliCodeService (foreground): installs, builds, agent runs, servers
```

Docs: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) ·
[`docs/RUNTIME.md`](docs/RUNTIME.md) ·
[`docs/BUILD.md`](docs/BUILD.md) ·
[`docs/MANUAL_TEST.md`](docs/MANUAL_TEST.md)

## Security notes

- PRoot is **filesystem namespacing, not a security sandbox** (no
  container/KVM isolation). Don't run untrusted code blindly.
- Tokens live in Android Keystore (AES/GCM); never plaintext.
- Downloads are SHA-256 verified; tar extraction blocks path traversal;
  destructive shell commands ask for confirmation.

## Licenses

EliCode's own code: MIT — see [LICENSE](LICENSE).
Third-party components (PRoot GPL-2.0, libandroid-shmem BSD-3-Clause,
talloc LGPL-3.0, ubuntu-base, Termux packaging): see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
