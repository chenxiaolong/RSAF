/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.res.Resources
import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.chiller3.rsaf.R
import kotlinx.parcelize.Parcelize

@Parcelize
sealed interface VfsCacheDeletionReason : Parcelable {
    fun getTitle(resources: Resources): String

    @Parcelize
    data object Import : VfsCacheDeletionReason {
        override fun getTitle(resources: Resources): String =
            resources.getString(R.string.dialog_import_password_title)

    }

    @Parcelize
    data class Rename(val remote: String) : VfsCacheDeletionReason {
        override fun getTitle(resources: Resources): String =
            resources.getString(R.string.dialog_rename_remote_title, remote)
    }

    @Parcelize
    data class Delete(val remote: String) : VfsCacheDeletionReason {
        override fun getTitle(resources: Resources): String =
            resources.getString(R.string.dialog_delete_remote_title, remote)
    }
}

@Composable
fun VfsCacheDeletionDialog(
    reason: VfsCacheDeletionReason,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current

    AlertDialog(
        title = { Text(text = reason.getTitle(resources)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = stringResource(R.string.dialog_vfs_cache_deletion_message))
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.dialog_action_proceed_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
