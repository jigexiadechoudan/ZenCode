package dev.zencode.skill;

/**
 * 目录型 Skill 自带工具的脚本解释器。
 */
public enum ScriptKind {
    /** PowerShell 脚本（.ps1）。 */
    POWERSHELL,
    /** Windows 批处理脚本（.bat / .cmd）。 */
    CMD
}
