/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.net.Uri

sealed interface SettingsAlert {
    data class ListRemotesFailed(val error: String) : SettingsAlert

    data class RemoteAddSucceeded(val remote: String) : SettingsAlert

    data class RemoteAddPartiallySucceeded(val remote: String) : SettingsAlert

    data object ImportSucceeded : SettingsAlert

    data object ExportSucceeded : SettingsAlert

    data class ImportFailed(val error: String) : SettingsAlert

    data class ExportFailed(val error: String) : SettingsAlert

    data object ImportCancelled : SettingsAlert

    data object ExportCancelled : SettingsAlert

    data class LogcatSucceeded(val uri: Uri) : SettingsAlert

    data class LogcatFailed(val uri: Uri, val error: String) : SettingsAlert
}
