package app.filemanagerx.data.fs

import com.topjohnwu.superuser.Shell

data class ShellOutput(
    val success: Boolean,
    val stdout: List<String>,
    val stderr: List<String>,
)

/**
 * Runs shell commands through libsu's persistent shell. Callers must only use it for mutating
 * commands while [isRootActive] is true; this class never decides that on its own.
 */
class ShellRunner(private val rootManager: RootManager) {
    val isRootActive: Boolean get() = rootManager.isRootModeActive

    /** Single-quotes [argument] so the shell treats it as one literal word. */
    fun quote(argument: String): String = "'" + argument.replace("'", "'\\''") + "'"

    /** Blocking; call from a background dispatcher. */
    fun execute(command: String): ShellOutput {
        val result = Shell.cmd(command).exec()
        return ShellOutput(result.isSuccess, result.out, result.err)
    }
}
