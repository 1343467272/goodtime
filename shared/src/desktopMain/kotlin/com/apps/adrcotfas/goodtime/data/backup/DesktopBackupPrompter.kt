/**
 *     Goodtime Productivity
 *     Copyright (C) 2025 Adrian Cotfas
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.apps.adrcotfas.goodtime.data.backup

import co.touchlab.kermit.Logger
import com.apps.adrcotfas.goodtime.backup.BackupPromptResult
import com.apps.adrcotfas.goodtime.backup.BackupPrompter
import com.apps.adrcotfas.goodtime.backup.BackupType
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Uses a native file chooser to pick the backup destination (save) and the backup
 * file to restore (open).
 */
class DesktopBackupPrompter(
    private val logger: Logger,
) : BackupPrompter {
    override suspend fun promptUserForBackup(
        backupType: BackupType,
        fileToSharePath: Path,
        callback: suspend (BackupPromptResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            selectedFile = File(fileToSharePath.name)
        }
        when (chooser.showSaveDialog(null)) {
            JFileChooser.APPROVE_OPTION -> {
                val destination = chooser.selectedFile ?: run {
                    callback(BackupPromptResult.CANCELLED)
                    return@withContext
                }
                try {
                    Files.copy(fileToSharePath.toNioPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    logger.d { "Backup saved to ${destination.absolutePath}" }
                    callback(BackupPromptResult.SUCCESS)
                } catch (e: Exception) {
                    logger.e(e) { "Failed to save backup" }
                    callback(BackupPromptResult.FAILED)
                }
            }

            else -> callback(BackupPromptResult.CANCELLED)
        }
    }

    override suspend fun promptUserForRestore(
        importedFilePath: String,
        callback: suspend (BackupPromptResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val chooser = JFileChooser()
        when (chooser.showOpenDialog(null)) {
            JFileChooser.APPROVE_OPTION -> {
                val source = chooser.selectedFile ?: run {
                    callback(BackupPromptResult.CANCELLED)
                    return@withContext
                }
                try {
                    Files.copy(source.toPath(), Paths.get(importedFilePath), StandardCopyOption.REPLACE_EXISTING)
                    logger.d { "Backup imported from ${source.absolutePath}" }
                    callback(BackupPromptResult.SUCCESS)
                } catch (e: Exception) {
                    logger.e(e) { "Failed to import backup" }
                    callback(BackupPromptResult.FAILED)
                }
            }

            else -> callback(BackupPromptResult.CANCELLED)
        }
    }
}
