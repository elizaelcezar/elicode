# EliCode Linux runtime

## What gets installed (and where)

Everything lives under the app-private dir `filesDir/elicode/runtime`
(never public storage):

| Path | Content |
|---|---|
| `tools/proot` | PRoot 5.1.107 aarch64 (Termux build, GPL-2.0) |
| `tools/lib/` | `libtalloc.so`, `libandroid-shmem.so` (`LD_LIBRARY_PATH`) |
| `rootfs/` | Ubuntu 22.04 base ARM64 (~30MB tgz, ~110MB extracted) |
| `downloads/` | Cached `.deb`/`.tgz` + `.part` resume files |
| `home/`, `tmp/` | Bind-mounted as `/root`, `/tmp` |
| `version.json` | Installed core version marker |

Pinned URLs + SHA-256: `app/src/main/assets/bootstrap/runtime.json`
(verified 2026-09-08 against `cdimage.ubuntu.com` and
`packages.termux.org`).

## Bootstrap sequence (`RuntimeInstaller`)

1. ARM64 ABI check + free-space check (1GB).
2. Download 3 `.deb`s (resume via HTTP Range, SHA-256 each).
3. Extract `data.tar.xz` from each (pure-JVM ar/tar/xz, path-traversal guard).
4. ELF check (`e_machine == 183`) + `proot --version` smoke test.
5. Download + SHA-256 + extract Ubuntu rootfs (progress by entries).
6. Write `resolv.conf`, `.bashrc`, then run `RuntimeValidator` core checks
   (arch, proot binary, proot runs, `bin/bash`, guest `echo`).
7. Only then write `version.json` — the app never shows "installed" early.

Cancel keeps the download cache; retry resumes. `Repair` re-extracts from
cache without downloading. Each package lists `url` + `mirrors` and the
installer tries them in order (HTTPS first — Android blocks cleartext http
by default, so an http-only primary URL used to fail every on-device
install). A checksum failure deletes both the cached file and its `.part`
so the next retry re-downloads instead of looping on the same bad bytes.
Settings → Runtime also offers "Clear downloads" (drop caches) and "Wipe
runtime" (delete the extracted tree + version marker) for manual recovery
from a half-finished install.

## Guest command shape (`ProotLauncher`)

```
proot -r rootfs -0 --kernel-release=5.15.0 \
  -b /dev -b /proc -b /sys \
  -b home:/root -b tmp:/tmp -b projects:/projects [-b ext:/ext] \
  -w <workdir> /bin/bash --login -c "<cmd>"
```

`-0` = fakeroot (apt/dpkg-friendly, no Android root needed).

## Optional toolchains (on demand, inside Ubuntu shell)

- Node/OpenCode: `assets/bootstrap/setup-node.sh`
  (`apt install nodejs`, `npm i -g opencode-ai`).
- Android builds: `assets/bootstrap/setup-android.sh`
  (JDK 17, Gradle 8.7, cmdline-tools, platform 34, build-tools 35).

## Limitations (honest)

- PRoot ≠ sandbox: no container/KVM isolation. Documented in-app.
- Pipe-based shells: no true PTY (no `vim`/job control) until the JNI
  bridge lands. Interactive stdin, streaming, cancel and process trees work.
- x86/x86_64 devices are rejected with a clear message (ARM64 binaries only).
