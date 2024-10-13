/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.extension

import android.system.ErrnoException
import com.chiller3.rsaf.binding.rcbridge.RbError
import java.io.IOException

fun RbError.toException(func: String): ErrnoException =
    ErrnoException(func, code.toInt(), IOException(msg))
