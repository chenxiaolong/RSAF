/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.Context
import android.content.Intent
import androidx.core.os.bundleOf
import com.chiller3.rsaf.PreferenceBaseActivity
import com.chiller3.rsaf.PreferenceBaseFragment

class EditRemoteActivity : PreferenceBaseActivity() {
    companion object {
        private const val EXTRA_REMOTE = "remote"

        const val RESULT_NEW_REMOTE = "new_remote"

        fun createIntent(context: Context, remote: String) =
            Intent(context, EditRemoteActivity::class.java).apply {
                putExtra(EXTRA_REMOTE, remote)
            }
    }

    private val remote: String by lazy {
        intent.getStringExtra(EXTRA_REMOTE)!!
    }

    override val actionBarTitle: CharSequence
        get() = remote

    override val showUpButton: Boolean = true

    override fun createFragment(): PreferenceBaseFragment = EditRemoteFragment().apply {
        arguments = bundleOf(EditRemoteFragment.ARG_REMOTE to remote)
    }
}
