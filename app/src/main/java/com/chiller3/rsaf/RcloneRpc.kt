/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.util.Log
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object RcloneRpc {
    private val TAG = RcloneRpc::class.java.simpleName

    private const val CUSTOM_OPT_PREFIX = "rsaf:"
    // This is called hidden due to backwards compatibility.
    const val CUSTOM_OPT_BLOCKED = CUSTOM_OPT_PREFIX + "hidden"
    const val CUSTOM_OPT_DYNAMIC_SHORTCUT = CUSTOM_OPT_PREFIX + "dynamic_shortcut"

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
            val remotes = output.optJSONArray("remotes") ?: return emptyArray()

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
        ProviderOption(JSONObject()
            .put("Name", "type")
            .put("FieldName", "")
            // Same string as in `rclone config`
            .put("Help", "Type of storage to configure.")
            .put("DefaultStr", "")
            .put("ValueStr", "")
            .put("Examples", JSONArray().apply {
                providers.forEach {
                    put(JSONObject()
                        .put("Value", it.key)
                        .put("Help", it.value.description)
                        .put("Provider", "")
                    )
                }
            })
            .put("Hide", 0)
            .put("Required", true)
            .put("IsPassword", false)
            .put("NoPrefix", false)
            .put("Advanced", false)
            .put("Exclusive", true)
            .put("Sensitive", false)
            .put("Type", "string")
        )
    }

    /**
     * A fake set of [ProviderOptionExample]s used when the option type is a boolean and no existing
     * examples are provided.
     */
    private val booleanExamples by lazy {
        listOf(
            ProviderOptionExample(JSONObject()
                .put("Value", "false")
                .put("Help", "false")
                .put("Provider", "")
            ),
            ProviderOptionExample(JSONObject()
                .put("Value", "true")
                .put("Help", "true")
                .put("Provider", "")
            ),
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
    class CommandHelp(data: JSONObject) {
        val name: String = data.getString("Name")
        val short: String = data.getString("Short")
        val long: String = data.getString("Long")
        val opts: Map<String, String> = mutableMapOf<String, String>().apply {
            data.optJSONObject("Opts")?.let { jsonOpts ->
                for (key in jsonOpts.keys()) {
                    put(key, jsonOpts.getString(key))
                }
            }
        }
    }

    @Suppress("unused")
    class MetadataHelp(data: JSONObject) {
        val help: String = data.getString("Help")
        val type: String = data.getString("Type")
        val example: String = data.getString("Example")
        val readOnly = data.getBoolean("ReadOnly")
    }

    @Suppress("unused")
    class MetadataInfo(data: JSONObject) {
        val system: Map<String, MetadataHelp> = mutableMapOf<String, MetadataHelp>().apply {
            data.optJSONObject("System")?.let { jsonSystem ->
                for (key in jsonSystem.keys()) {
                    put(key, MetadataHelp(jsonSystem.getJSONObject(key)))
                }
            }
        }
        val help: String = data.getString("Help")
    }

    @Suppress("unused")
    class Provider(data: JSONObject) {
        val name: String = data.getString("Name")
        val description: String = data.getString("Description")
        val prefix: String = data.getString("Prefix")
        val options: List<ProviderOption> = mutableListOf<ProviderOption>().apply {
            val jsonOptions = data.getJSONArray("Options")

            for (i in 0 until jsonOptions.length()) {
                add(ProviderOption(jsonOptions.getJSONObject(i)))
            }
        }
        val commandHelp: List<CommandHelp> = mutableListOf<CommandHelp>().apply {
            data.optJSONArray("CommandHelp")?.let { jsonCommandHelp ->
                for (i in 0 until jsonCommandHelp.length()) {
                    add(CommandHelp(jsonCommandHelp.getJSONObject(i)))
                }
            }
        }
        val aliases: List<String> = mutableListOf<String>().apply {
            data.optJSONArray("Aliases")?.let { jsonAliases ->
                for (i in 0 until jsonAliases.length()) {
                    add(jsonAliases.getString(i))
                }
            }
        }
        val hide = data.getBoolean("Hide")
        val metadataInfo = data.optJSONObject("MetadataInfo")?.let { MetadataInfo(it) }
    }

    @Suppress("unused")
    class ProviderOptionExample(data: JSONObject) {
        val value: String = data.getString("Value")
        val help: String = data.getString("Help")
        val provider: String = data.optString("Provider")
    }

    @Suppress("unused")
    class ProviderOption(data: JSONObject) {
        val name: String = data.getString("Name")
        val fieldName: String = data.getString("FieldName")
        val help: String = data.getString("Help")
        val groups: String = data.optString("Groups")
        val provider: String = data.optString("Provider")
        val default: String = data.getString("DefaultStr")
        val value: String = data.getString("ValueStr")
        val shortOpt: String = data.optString("ShortOpt")
        val hide = data.getInt("Hide") != 0
        val required = data.getBoolean("Required")
        val isPassword = data.getBoolean("IsPassword")
        val noPrefix = data.getBoolean("NoPrefix")
        val advanced = data.getBoolean("Advanced")
        val type: String = data.getString("Type")
        val examples: List<ProviderOptionExample> = mutableListOf<ProviderOptionExample>().apply {
            data.optJSONArray("Examples")?.let { jsonExamples ->
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
        val sensitive = data.getBoolean("Sensitive")

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
            val input = JSONObject()
                .put("name", remote)
                // The parameters field is required to exist even in interactive mode.
                .put("parameters", JSONObject())
                .put("opt", JSONObject()
                    .put("nonInteractive", true)
                    .put("all", true)
                    .put("obscure", true)
                    .apply {
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
                    }
                )
                .apply {
                    if (create) {
                        put("type", answer)
                    }
                }

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
        invoke("config/update", JSONObject()
            .put("name", remote)
            .put("parameters", JSONObject()
                .apply {
                    for ((k, v) in options.entries) {
                        put(k, v)
                    }
                }
            )
            // This is required or else the rclone authorize flow is triggered, even if we don't
            // update any authentication-related options.
            .put("opt", JSONObject()
                .put("nonInteractive", true)
                .put("obscure", true)
            )
        )
    }

    /** Convert plain-text password to rclone-obscured password. */
    private fun obscure(password: String): String {
        val output = invoke("core/obscure", JSONObject(mapOf("clear" to password)))

        return output.getString("obscured")
    }
}