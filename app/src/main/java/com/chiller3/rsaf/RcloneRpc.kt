package com.chiller3.rsaf

import android.util.Log
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import org.json.JSONObject
import java.io.IOException

object RcloneRpc {
    private val TAG = RcloneRpc::class.java.simpleName

    private const val CUSTOM_OPT_PREFIX = "rsaf:"
    const val CUSTOM_OPT_HIDDEN = CUSTOM_OPT_PREFIX + "hidden"

    /**
     * Perform an rclone RPC call.
     *
     * @throws IOException if the RPC call does not return a 200 status
     */
    private fun invoke(method: String, input: JSONObject): JSONObject {
        val result = Rcbridge.rbRpcCall(method, input.toString())

        when (method) {
            "config/create", "config/delete", "config/update" -> RcloneConfig.notifyConfigChanged()
            else -> Log.d(TAG, "No backup notification required for $method RPC call")
        }

        if (result.status != 200L) {
            throw IOException("$method call failed with status ${result.status}")
        }

        return JSONObject(result.output)
    }

    /** List of all rclone remotes from the config (without the colon). */
    val remoteNames: Array<String>
        get() {
            val output = invoke("config/listremotes", JSONObject())
            val remotes = output.getJSONArray("remotes")

            return Array(remotes.length()) {
                remotes.getString(it)
            }
        }

    /** All rclone remotes, along with their configurations. */
    val remotes: Map<String, Map<String, String>>
        get() {
            val output = invoke("config/dump", JSONObject())
            val result = mutableMapOf<String, Map<String, String>>()

            for (remote in output.keys()) {
                val configJson = output.getJSONObject(remote)
                val config = mutableMapOf<String, String>()

                for (key in configJson.keys()) {
                    config[key] = configJson.getString(key)
                }

                result[remote] = config
            }

            return result
        }

    /**
     * Get all rclone providers.
     *
     * This is computed once and cached forever.
     */
    val providers: Map<String, Provider> by lazy {
        val output = invoke("config/providers", JSONObject())
        val providersJson = output.getJSONArray("providers")
        val result = mutableMapOf<String, Provider>()

        for (i in 0 until providersJson.length()) {
            val provider = Provider(providersJson.getJSONObject(i))
            result[provider.name] = provider
        }

        result
    }

    /**
     * A fake interactive question for selecting a provider type.
     *
     * This is computed once and cached forever.
     */
    private val providerQuestion by lazy {
        ProviderOption(JSONObject(mapOf(
            "Name" to "type",
            // Same string as in `rclone config`
            "Help" to "Type of storage to configure.",
            "DefaultStr" to "",
            "ValueStr" to "",
            "Examples" to mutableListOf<Map<String, Any?>>().apply {
                providers.forEach {
                    add(mapOf(
                        "Value" to it.key,
                        "Help" to it.value.description,
                        "Provider" to "",
                    ))
                }
            },
            "Hide" to 0,
            "Required" to true,
            "IsPassword" to false,
            "Advanced" to false,
            "Exclusive" to true,
            "Type" to "string",
        )))
    }

    /**
     * A fake set of [ProviderOptionExample]s used when the option type is a boolean and no existing
     * examples are provided.
     */
    private val booleanExamples by lazy {
        listOf(
            ProviderOptionExample(JSONObject(mapOf<String, Any?>(
                "Value" to "false",
                "Help" to "false",
                "Provider" to "",
            ))),
            ProviderOptionExample(JSONObject(mapOf<String, Any?>(
                "Value" to "true",
                "Help" to "true",
                "Provider" to "",
            ))),
        )
    }

    /** Delete a remote from the configuration. */
    fun deleteRemote(remote: String) {
        invoke("config/delete", JSONObject(mapOf("name" to remote)))

        // The RPC call will not clear out fs/vfs caches, so we'll do that manually here. Otherwise,
        // we might reference stale data if the user creates a new remote with the same name.
        Rcbridge.rbCacheClearRemote("$remote:")
    }

    @Suppress("unused")
    class Provider(data: JSONObject) {
        val name: String = data.getString("Name")
        val description: String = data.getString("Description")
        val options: List<ProviderOption> = mutableListOf<ProviderOption>().apply {
            val jsonOptions = data.getJSONArray("Options")

            for (i in 0 until jsonOptions.length()) {
                add(ProviderOption(jsonOptions.getJSONObject(i)))
            }
        }
        val hide = data.getBoolean("Hide")
    }

    @Suppress("unused")
    class ProviderOptionExample(data: JSONObject) {
        val value: String = data.getString("Value")
        val help: String = data.getString("Help")
        val provider: String = data.getString("Provider")
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    class ProviderOption(data: JSONObject) {
        val name: String = data.getString("Name")
        val help: String = data.getString("Help")
        val default: String = data.getString("DefaultStr")
        val value: String = data.getString("ValueStr")
        val hide = data.getInt("Hide") != 0
        val required = data.getBoolean("Required")
        val isPassword = data.getBoolean("IsPassword")
        val advanced = data.getBoolean("Advanced")
        val type: String = data.getString("Type")
        val examples: List<ProviderOptionExample> = mutableListOf<ProviderOptionExample>().apply {
            if (data.has("Examples")) {
                val jsonExamples = data.getJSONArray("Examples")

                for (i in 0 until jsonExamples.length()) {
                    add(ProviderOptionExample(jsonExamples.getJSONObject(i)))
                }
            }
            // Add fake options for better UX
            if (type == "bool" && isEmpty()) {
                addAll(booleanExamples)
            }
        }
        val exclusive = data.getBoolean("Exclusive") ||
            // rclone doesn't mark boolean options as exclusive, so fake it ourselves for better UX
            (type == "bool" && examples.map { it.value }.toSet() == setOf("false", "true"))

        // To support our custom authorizer
        val isAuthorize = name == "config_token"
        val authorizeCmd by lazy {
            if (!isAuthorize) {
                throw IllegalStateException("Not an authorize question")
            }

            val cmd = help.splitToSequence('\n')
                .map { it.trim() }
                .find { it.startsWith("rclone authorize ") }
                ?: throw IllegalStateException("Help does not list authorize command: $help")

            cmd
        }

        init {
            // The UI assumes that there will never be 0 exclusive options
            if (exclusive && examples.isEmpty()) {
                throw IllegalStateException("Exclusive option, but no choices: $data")
            }
        }
    }

    class InteractiveConfiguration(private val remote: String) {
        private var create = remote !in remoteNames
        private var state: String? = null
        private var error: String? = null
        private var option: ProviderOption? = if (create) { providerQuestion } else { null }

        init {
            // If we're not creating a new remote, we need to start the config process to get the
            // first question
            if (!create) {
                submit(null)
            }
        }

        /**
         * Get the interactive question to show the user.
         *
         * This does not mutate state and is safe to access multiple times.
         *
         * Returns an optional error message that should be shown to the user and the question that
         * should be answered. If there are no more questions, then null is returned.
         */
        val question: Pair<String?, ProviderOption>?
            get() = option?.let { Pair(error, it) }

        fun submit(answer: String?) {
            val input = JSONObject(
                mutableMapOf<String, Any?>(
                    "name" to remote,
                    // The parameters field is required to exist even in interactive mode
                    "parameters" to JSONObject(),
                    "opt" to mutableMapOf<String, Any?>(
                        "nonInteractive" to true,
                        "all" to true,
                        "obscure" to true,
                    ).apply {
                        state?.let {
                            put("state", it)
                        }
                        if (!create) {
                            val result = answer?.let {
                                // The "obscure" flag is currently ignored for the "result" value:
                                // https://github.com/rclone/rclone/issues/7069
                                if (option?.isPassword == true) {
                                    obscure(it)
                                } else {
                                    it
                                }
                            }

                            put("continue", true)
                            put("result", result)
                        }
                    },
                ).apply {
                    if (create) {
                        put("type", answer)
                    }
                }
            )

            val method = if (create) {
                "config/create"
            } else {
                "config/update"
            }

            val output = invoke(method, input)
            create = false

            state = output.getString("State")
            option = if (output.isNull("Option")) {
                null
            } else {
                ProviderOption(output.getJSONObject("Option"))
            }

            // We require either using Authorizer or having the user manually run rclone authorize.
            // If true is selected, the question prints to stdout and blocks until the authorization
            // is complete.
            if (option?.name == "config_is_local") {
                submit("false")
            }
        }
    }

    /** Directly and non-interactively set config key/value pairs for a remote. */
    fun setRemoteOptions(remote: String, options: Map<String, String>) {
        invoke("config/update", JSONObject(
            mutableMapOf<String, Any?>(
                "name" to remote,
                "parameters" to options,
                "opt" to mutableMapOf<String, Any?>(
                    "obscure" to true,
                ),
            ),
        ))
    }

    /** Convert plain-text password to rclone-obscured password. */
    private fun obscure(password: String): String {
        val output = invoke("core/obscure", JSONObject(mapOf("clear" to password)))

        return output.getString("obscured")
    }
}