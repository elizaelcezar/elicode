package com.elicode.app.runtime

/**
 * Multi-arch selection for the Linux runtime.
 *
 * Pure Kotlin (no Android dependency) so it is unit-testable on the JVM.
 * Prefers ARM64 when the device exposes both ABIs (real device wins
 * over emulator), otherwise falls back to x86_64 (emulators).
 */
object ArchSupport {

    const val ARM64 = "arm64"
    const val X86_64 = "x86_64"

    /** Returns "arm64", "x86_64" or "" when neither ABI is present. */
    fun selectArch(abis: List<String>): String = when {
        abis.any { it == "arm64-v8a" } -> ARM64
        abis.any { it == "x86_64" } -> X86_64
        else -> ""
    }

    fun isSupported(abis: List<String>): Boolean = selectArch(abis).isNotEmpty()

    /** Expected ELF e_machine for the PRoot binary of [arch]. */
    fun expectedElf(arch: String): Int = when (arch) {
        X86_64 -> ArchiveExtractor.EM_X86_64
        else -> ArchiveExtractor.EM_AARCH64
    }

    /** Human label for UI / diagnostics. */
    fun abiLabel(arch: String): String = when (arch) {
        X86_64 -> "x86_64"
        ARM64 -> "arm64-v8a"
        else -> "unsupported"
    }
}
