package com.chiller3.rsaf

import android.util.Log
import com.chiller3.rsaf.binding.rcbridge.RbError
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A hacky class that does what `rclone authorize` does.
 *
 * Because the internal APIs aren't sufficient for library usage, this runs the function backing
 * the `rclone authorize` CLI command and relies on parsing the logcat to gather the results.
 */
object Authorizer {
    private val TAG = Authorizer::class.java.simpleName
    private const val GO_TAG = "GoLog"

    private val MARKER_CANCEL = randomMarker("cancel")
    private const val MARKER_URL = "Please go to the following link: "
    private const val MARKER_CODE_START = "Paste the following into your remote machine --->"
    private const val MARKER_CODE_STOP = "<---End paste"

    private fun randomMarker(type: String) =
        "Log marker for '$type': ${RandomUtils.generatePassword(32)}"

    /**
     * Parse the `rclone authorize` command in the format rclone provides in the config question.
     *
     * We don't need a sophisticated parser. The string comes from oauthutil.ConfigOAuth() and the
     * arguments produced are `rclone`, `authorize`, a backend name, and an optional base64-encoded
     * string.
     */
    private fun parseCmd(cmdString: String): List<String> =
        cmdString.replace("\"", "")
            .split("\\s+".toRegex())
            .apply {
                if (size < 2 || this[0] != "rclone" || this[1] != "authorize") {
                    throw IllegalArgumentException("Invalid authorize command: $this")
                }
            }
            .drop(2)

    private fun logAsIfGo(msg: String) {
        Log.i(GO_TAG, msg)
    }

    private fun authorizeBlockingLocked(cmd: String, listener: AuthorizeListener) {
        val args = parseCmd(cmd)

        Log.d(TAG, "Starting logcat")
        val logcat = ProcessBuilder("logcat", "-v", "raw", "*:S", "$GO_TAG:V")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectErrorStream(true)
            .start()

        try {
            val markerNow = randomMarker("start")
            logAsIfGo(markerNow)

            val server = Thread {
                Log.d(TAG, "Starting authorize server for $args")

                val error = RbError()
                if (!Rcbridge.rbAuthorize(args.joinToString("\u0000"), error)) {
                    // Intentionally no stack trace
                    Log.w(TAG, "rbAuthorize error: ${error.msg}")
                }

                Log.d(TAG, "Stopped authorize server")
                cancelLogReader()
            }.apply { start() }

            try {
                processLogs(logcat.inputStream, markerNow, listener)
            } catch (e: Exception) {
                cancelServer()
            } finally {
                server.join()
            }
        } finally {
            try {
                logcat.destroy()
            } finally {
                logcat.waitFor()
            }
        }
    }

    /**
     * Start an `rclone authorize` server and wait for the URL and token.
     *
     * [listener]'s callbacks are invoked on the calling thread. [AuthorizeListener.onAuthorizeUrl]
     * will be called as soon as the URL is known so that it can be shown to the user. Once the user
     * visits the URL and completes the authorization, the token will be reported via
     * [AuthorizeListener.onAuthorizeCode].
     */
    fun authorizeBlocking(cmd: String, listener: AuthorizeListener) {
        synchronized(this) {
            authorizeBlockingLocked(cmd, listener)
        }
    }

    /**
     * Try to cancel the running `rclone authorize` server.
     *
     * This should hopefully work, but maybe not since we're relying on log parsing. This function
     * is idempotent and does not throw exceptions.
     */
    fun cancel() {
        cancelLogReader()
        cancelServer()
    }

    private fun processLogs(inputStream: InputStream, startMarker: String,
                            listener: AuthorizeListener) {
        var skippedOld = false
        var inCode = false
        val code = StringBuilder()

        inputStream.use { stream ->
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break

                    if (!skippedOld) {
                        if (line.contains(startMarker)) {
                            skippedOld = true
                        }
                        continue
                    }

                    if (line.contains(MARKER_CANCEL)) {
                        break
                    }

                    if (!inCode) {
                        val pos = line.indexOf(MARKER_URL)
                        if (pos >= 0) {
                            val url = line.substring(pos + MARKER_URL.length)
                            listener.onAuthorizeUrl(url)
                            continue
                        }
                    }

                    if (line.contains(MARKER_CODE_START)) {
                        inCode = true
                    } else if (line.contains(MARKER_CODE_STOP)) {
                        inCode = false
                        listener.onAuthorizeCode(code.toString())
                    } else if (inCode) {
                        code.append(line)
                    }
                }
            }
        }
    }

    private fun cancelLogReader() {
        logAsIfGo(MARKER_CANCEL)
    }

    private fun cancelServer() {
        // This is horrendous, but the only way to kill the server is by sending a bad request.
        try {
            val conn = URL(Rcbridge.rbAuthorizeUrl()).openConnection() as HttpURLConnection
            val response = if (conn.responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                conn.errorStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } else {
                conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            }

            Log.w(TAG, "Sent bad request to kill authorize server; " +
                    "response: ${response.length} bytes")
        } catch (e: Exception) {
            // Intentionally omitting the stack trace because this path is "normal"
            Log.w(TAG, "Error when cancelling authorize server: $e")
        }
    }

    interface AuthorizeListener {
        fun onAuthorizeUrl(url: String)

        fun onAuthorizeCode(code: String)
    }
}