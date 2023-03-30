package com.chiller3.rsaf

import android.system.ErrnoException
import com.chiller3.rsaf.binding.rcbridge.RbError
import java.io.IOException

fun RbError.toException(func: String): ErrnoException =
    ErrnoException(func, code.toInt(), IOException(msg))
