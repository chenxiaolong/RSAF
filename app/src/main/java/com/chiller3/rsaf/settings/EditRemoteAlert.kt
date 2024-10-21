/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

sealed interface EditRemoteAlert {
    data class ListRemotesFailed(val error: String) : EditRemoteAlert

    data class RemoteEditSucceeded(val remote: String) : EditRemoteAlert

    data class RemoteDeleteFailed(val remote: String, val error: String) : EditRemoteAlert

    data class RemoteRenameFailed(
        val oldRemote: String,
        val newRemote: String,
        val error: String,
    ) : EditRemoteAlert

    data class RemoteDuplicateFailed(
        val oldRemote: String,
        val newRemote: String,
        val error: String,
    ) : EditRemoteAlert

    data class UpdateExternalAccessFailed(val remote: String, val error: String) : EditRemoteAlert

    data class UpdateDynamicShortcutFailed(val remote: String, val error: String) : EditRemoteAlert
}
