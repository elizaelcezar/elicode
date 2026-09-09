# EliCode architecture

```
┌─────────────────────────────────────────────────────────┐
│ Android Host (this APK)                                 │
│  Compose UI ──► SessionState ──► Engines ──► RuntimeMgr  │
│   Projects / Editor (highlight, undo, search)           │
│   Terminal (interactive shell, registry)                │
│   Agent (OpenCode via guest npm/node)                   │
│   Git + GitHub (Keystore token, REST, git binary)       │
│   Preview (server → port sniff → WebView)               │
│   Build (guest gradle → APK/AAB → PackageInstaller)     │
│   EliCodeService (foreground, dataSync)                 │
└──────────────────────────┬──────────────────────────────┘
                           │ ProcessBuilder
                           ▼
┌─────────────────────────────────────────────────────────┐
│ Linux runtime (app-private filesDir/elicode/runtime)    │
│  tools/proot (aarch64, +libtalloc/shmem)                │
│  rootfs/  Ubuntu 22.04 ARM64 (proot -r, fakeroot -0)    │
│  home/→/root  tmp/→/tmp  projects/→/projects  /ext      │
│  node · npm · opencode · git · java · gradle · SDK      │
│  (base always; toolchains on demand)                    │
└─────────────────────────────────────────────────────────┘
```

Key interfaces (`runtime/`): `ProcessRunner`, `EliProcess`,
`ProcessListener`, `GitShell`. Engines depend on interfaces, so the
future JNI/PTY bridge (`native/src/proot_bridge.cpp`, `NativeBridge`)
can replace `JvmProcessRunner` without touching callers.

Data flow rules:

- UI never blocks: engines expose callbacks/StateFlow; long work goes
  through `ProcessRegistry` + `EliCodeService`.
- Every engine failure returns `EliResult.Err(EliError)` — operation,
  command, exit code, raw message, probable cause, suggested fix.
- Tokens: `GitHubApi` receives them per call; only `KeystoreStore`
  persists them (AES/GCM, AndroidKeyStore).
- Projects: `filesDir/elicode/projects/` by default; SAF trees are
  *copied* in (import) because SAF URIs are not `File` paths.
