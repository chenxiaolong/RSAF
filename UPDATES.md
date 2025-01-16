# Updating rclone

This document lists all of the areas that may need tweaking when updating the rclone version.

(None of the statements below are criticism of rclone. It's designed to be an application and the fact that it works as a library to the extent that it currently does is awesome!)

## [`rcbridge.go`](rcbridge/rcbridge.go)

Just about everything in this file could potentially break because it relies in internal rclone APIs. The notable components are documented below.

### Basic compilation

Hopefully, most issues can be found just by trying to compile rcbridge with `./gradlew rcbridge` and looking at the compilation errors. Most minor updates just involve some function signatures that were changed.

### Error map

`errMap` should include all errors from `<rclone>/vfs/errors.go` and errors that map cleanly to `errno` from `<rclone>/fs/fs.go`. Otherwise, newly added errors will not be mapped to an `errno` value and will fall back to `EIO` when the error is passed to the Android side.

### librclone

If librclone gained new functionality that can replace current uses of internal APIs, then that new functionality should be used. librclone RPC-related tasks should be done purely on the Android side in [`RcloneRpc`](./app/src/main/java/com/chiller3/rsaf/rclone/RcloneRpc.kt), not in go.

### Certificate reloading

Check if there's any way hook into `fshttp.(*Transport).RoundTrip()`. The hook to update `tls.Config.RootCAs` is the only reason we need to fork rclone.

### `RbDocMkdir`

Check the `vfs.Dir.Mkdir()` implementation to see if it fails with EEXIST when the path already exists. If so, `RcloneProvider` can be updated to avoid an unnecessary stat when creating directories with Android semantics.

### `RbDocRename`

Check the `vfs.VFS.Rename()` implementation to see if the error handling changed. If the errors are now more granular, update rcbridge accordingly so that it doesn't return `EIO` for everything.

### `RbDocCopyOrMove`

Check the VFS implementation to see if there are new copy/move related functions implemented there. This is the only function that relies on the low-level `fs.Fs` API. It would be nice to be able to use the high-level `vfs.VFS` for everything, if possible (without sacrificing features like server-side copy).

Also, check `sync.MoveDir()` to see if still leaves behind an empty source directory when moving to an existing target directory. If not, we can drop the `sourceFs.Rmdir()` workaround.

### `RbAuthorize`

Check if there is a more library-friendly API for doing what `rclone authorize` does. Currently, the authorization flow is done in a blocking way. It starts a webserver to handle the oauth flow and waits for the authorization to complete before returning. If the user wants to cancel the authorization process, the only way to do so is by sending an invalid request to the webserver or by killing the (Linux) process entirely.

In addition, the URL and token are sent to stdout (or the logs). RSAF currently has to resort to parsing the logcat output to retrieve that information.

## [`Authorizer.kt`](./app/src/main/java/com/chiller3/rsaf/rclone/Authorizer.kt)

### Log markers

Check all of the `MARKER_` constants to make sure the strings match what's in rclone's source code. Due to the constraints mentioned in the [`RbAuthorize`](#rbauthorize) section, we currently have to rely on parsing the logcat and looking for specific strings.

Also, check that `GO_TAG` still matches the logcat tag that gomobile uses.

### Command parsing

Check the `oauthutil.ConfigOAuth()` implementation to see if the way that the interactive question shows the `rclone authorize` command is still the same. Currently, it uses `%q` to format values, which happen to never have characters that need to be escaped. If that changes, then the parser in `parseCmd` needs to be updated accordingly.

### Server cancellation

Currently, the only way to stop the `rclone authorize` server is by sending it a bad request. Check that this mechanism still works. This can be done on the command line by running `rclone authorize <backend>` and then making a GET request to http://localhost:53682/.

## [`RcloneConfig.kt`](./app/src/main/java/com/chiller3/rsaf/rclone/RcloneConfig.kt)

### Error messages

Check if `crypt.Decrypt()` has a distinct error type for missing/invalid passwords. If not, make sure that `ERROR_BAD_PASSWORD` still matches the error string.

### Global state

Check if there are new APIs for loading/saving the configuration without setting the path and password globally.

### Files

Check if there are new APIs for loading/saving config files from/to in-memory buffers.

## [`RcloneRpc.kt`](./app/src/main/java/com/chiller3/rsaf/rclone/RcloneRpc.kt)

### Providers question

Check if rclone added a builtin way of asking for the remote type when creating a new remote. (`rclone config` uses `config.fsOption()`, which isn't exported.) If so, we can remove our fake injected question for it.

### Authorize question

Check if the question for whether to immediately perform authorization or have the user run `rclone authorize` is still named `config_is_local`.
