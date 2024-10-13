// SPDX-FileCopyrightText: 2023 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

package envhack

/*
#include <unistd.h>
*/
import "C"
import (
	"os"
	"strings"
	"unsafe"
)

func init() {
	// Golang has its own internal copy of the environment variables. When using
	// go as a shared library, the internal map never gets populated from
	// _start()'s envp, so no environment variables are accessible. This
	// terrible hack allows us to explicitly copy the environment variables from
	// libc.

	ptr := C.environ

	for *ptr != nil {
		key_value := C.GoString(*ptr)
		pieces := strings.SplitN(key_value, "=", 2)

		if len(pieces) == 2 {
			os.Setenv(pieces[0], pieces[1])
		}

		ptr = (**C.char)(unsafe.Add(unsafe.Pointer(ptr), unsafe.Sizeof(ptr)))
	}
}
