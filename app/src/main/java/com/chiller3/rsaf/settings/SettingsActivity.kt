/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import com.chiller3.rsaf.PreferenceBaseActivity
import com.chiller3.rsaf.PreferenceBaseFragment

class SettingsActivity : PreferenceBaseActivity() {
    override val actionBarTitle: CharSequence? = null

    override val showUpButton: Boolean = false

    override fun createFragment(): PreferenceBaseFragment = SettingsFragment()
}
