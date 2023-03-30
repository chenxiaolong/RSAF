package com.chiller3.rsaf

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chiller3.rsaf.databinding.DialogInteractiveConfigurationBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlin.properties.Delegates

class InteractiveConfigurationDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = InteractiveConfigurationDialogFragment::class.java.simpleName

        private const val ARG_REMOTE = "remote"
        private const val ARG_NEW = "new"
        const val RESULT_REMOTE = ARG_REMOTE
        const val RESULT_NEW = ARG_NEW
        const val RESULT_CANCELLED = "cancelled"
        private const val STATE_LAST_OPTION_NAME = "last_option_name"
        private const val STATE_USER_INPUT = "user_input"

        fun newInstance(remote: String, new: Boolean): InteractiveConfigurationDialogFragment =
            InteractiveConfigurationDialogFragment().apply {
                arguments = bundleOf(
                    ARG_REMOTE to remote,
                    ARG_NEW to new,
                )
            }

        /** Replace newlines with spaces unless there are multiple newlines in a row. */
        private fun reflowString(msg: String): String =
            msg.replace("([^\\n])\\n([^\\n]|\$)".toRegex(), "$1 $2")
    }

    private var cancelled = true
    private var isNew by Delegates.notNull<Boolean>()
    private val viewModel: InteractiveConfigurationViewModel by viewModels()
    private lateinit var binding: DialogInteractiveConfigurationBinding
    private var radioToValue = hashMapOf<Int, String>()
    private var ignoreRadioChanged = false
    private var userInput = ""
    private var lastOptionName: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val remote = arguments.getString(ARG_REMOTE)!!
        isNew = arguments.getBoolean(ARG_NEW)

        if (savedInstanceState == null) {
            viewModel.init(remote)
        } else {
            lastOptionName = savedInstanceState.getString(STATE_LAST_OPTION_NAME)
            userInput = savedInstanceState.getString(STATE_USER_INPUT)!!
        }

        binding = DialogInteractiveConfigurationBinding.inflate(layoutInflater)

        binding.useDefault.setOnCheckedChangeListener { _, _ ->
            refreshDefaultEnabledState()
            refreshNextButtonEnabledState()
        }

        binding.text.addTextChangedListener {
            userInput = it.toString()
            setExampleSelectionFromInput()
            refreshNextButtonEnabledState()
        }

        binding.examplesGroup.setOnCheckedChangeListener { _, checkedId ->
            if (!ignoreRadioChanged) {
                // On configuration change, this may run prior to viewModel.question.collect
                radioToValue[checkedId]?.let {
                    userInput = it
                    setTextToInput()
                    refreshNextButtonEnabledState()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.run.collect {
                    if (!it) {
                        // No more questions. We can just exit because changes are immediately
                        // committed upon submission.
                        cancelled = false
                        dismiss()
                    }
                }
            }
        }

        val title = if (isNew) {
            getString(R.string.ic_title_add_remote, remote)
        } else {
            getString(R.string.ic_title_edit_remote, remote)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(R.string.dialog_action_next, null)
            .setNegativeButton(R.string.dialog_action_cancel, null)
            .setNeutralButton(R.string.dialog_action_authorize, null)
            // Don't lose user state unless the user cancels intentionally
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)

                // Set click handlers manually because doing it via the alert dialog builder forces
                // the buttons to always dismiss the dialog
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val (answer, ok) = getSubmission()
                        if (!ok) {
                            throw IllegalStateException(
                                "Next button was able to be pressed with invalid answer")
                        }

                        viewModel.submit(answer)
                    }
                    getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        dismiss()
                    }
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val cmd = viewModel.question.value!!.second.authorizeCmd

                        AuthorizeDialogFragment.newInstance(cmd)
                            .show(parentFragmentManager.beginTransaction(),
                                AuthorizeDialogFragment.TAG)
                    }
                }
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.question.collect { question ->
                    val (error, option) = question ?: return@collect

                    updateMessage(error, option)
                    updateInput(option)
                    updateExamples(option)
                    updateDefaultToggle(option)

                    refreshDefaultEnabledState()

                    userInput = if (lastOptionName == option.name) {
                        // Configuration change or repeated question after rejected answer
                        userInput
                    } else if (option.value.isNotEmpty()) {
                        option.value
                    } else if (option.default.isNotEmpty()) {
                        option.default
                    } else {
                        ""
                    }

                    setTextToInput()
                    setExampleSelectionFromInput()
                    refreshNextButtonEnabledState()

                    lastOptionName = option.name
                }
            }
        }

        setFragmentResultListener(AuthorizeDialogFragment.TAG) { _, bundle: Bundle ->
            userInput = bundle.getString(AuthorizeDialogFragment.RESULT_CODE)!!
            setTextToInput()
            refreshNextButtonEnabledState()
        }

        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        // Just to signal completion to the parent
        setFragmentResult(tag!!, bundleOf(
            RESULT_REMOTE to requireArguments().getString(ARG_REMOTE),
            RESULT_NEW to requireArguments().getBoolean(ARG_NEW),
            RESULT_CANCELLED to cancelled,
        ))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LAST_OPTION_NAME, lastOptionName)
        outState.putString(STATE_USER_INPUT, userInput)
    }

    private fun setTextToInput() {
        if (binding.text.text.toString() != userInput) {
            binding.text.setText(userInput)
        }
    }

    private fun setExampleSelectionFromInput() {
        val exampleId = radioToValue.entries.find { it.value == userInput }?.key
        if (exampleId != null) {
            binding.examplesGroup.check(exampleId)
        } else {
            // When clearing the checked state, the OnCheckedChangeListener is called twice: once
            // for the currently selected item and again for -1. In the listener, there's no way to
            // detect and ignore that first invocation, so we'll have to settle for this ugly
            // workaround.
            ignoreRadioChanged = true
            binding.examplesGroup.clearCheck()
            ignoreRadioChanged = false
        }
    }

    /**
     * Update the main dialog message.
     *
     * If [error] is specified, it represents an error with the previously submitted answer. In this
     * scenario, the question is likely the same as before because the bad answer prevented
     * progression. The error message is shown on its own line prior to the new question.
     */
    private fun updateMessage(error: String?, option: RcloneRpc.ProviderOption) {
        binding.message.text = buildString {
            if (error != null) {
                append(reflowString(error))
                append("\n\n")
            }
            append(reflowString(option.help))
        }
    }

    /**
     * Update input field UI state.
     *
     * If the question does not use exclusive options, a text box will be shown. This does not
     * change the contents of the text box.
     */
    private fun updateInput(option: RcloneRpc.ProviderOption) {
        if (option.exclusive) {
            binding.textLayout.isVisible = false
        } else {
            binding.textLayout.isVisible = true

            binding.textLayout.hint = option.name

            if (option.required) {
                binding.textLayout.helperText =
                    getString(R.string.ic_text_box_helper_required)
            } else {
                binding.textLayout.helperText =
                    getString(R.string.ic_text_box_helper_not_required)
            }

            binding.text.inputType = InputType.TYPE_CLASS_TEXT or if (option.isPassword) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                0
            }
        }
    }

    /**
     * Update examples UI state.
     *
     * This will clear existing radio buttons and create new ones for each example. If the examples
     * are not exclusive, then the examples header is shown to notify the user that the radio
     * buttons are just examples.
     */
    private fun updateExamples(option: RcloneRpc.ProviderOption) {
        radioToValue.clear()
        binding.examplesGroup.removeAllViews()

        if (option.examples.isEmpty()) {
            binding.examplesHeader.isVisible = false
            binding.examplesGroup.isVisible = false
        } else {
            binding.examplesHeader.isVisible = !option.exclusive
            binding.examplesGroup.isVisible = true

            val context = requireContext()

            for (example in option.examples) {
                val radioButton = RadioButton(context).apply {
                    id = View.generateViewId()
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    // Not translatable. rclone always provides English strings anyway.
                    @SuppressLint("SetTextI18n")
                    text = "${example.value} (${example.help})"
                }
                radioToValue[radioButton.id] = example.value
                binding.examplesGroup.addView(radioButton)
            }
        }
    }

    /**
     * Update UI state of the the "use default/current" toggle.
     *
     * To match the `rclone config` behavior, if there's a current value, it takes precedence over
     * the default value unless it is equal to the default value. If there's no valid current or
     * default value, then the toggle's checked state is set to false and the toggle will be hidden.
     */
    private fun updateDefaultToggle(option: RcloneRpc.ProviderOption) {
        if ((option.value.isNotEmpty() && option.value != option.default) || !isNew) {
            binding.useDefault.text = getString(R.string.ic_use_current_value, option.value)
        } else {
            binding.useDefault.text = getString(R.string.ic_use_default_value, option.default)
        }
        binding.useDefault.isVisible =
            option.value.isNotEmpty() && option.default.isNotEmpty()
        if (option.name != lastOptionName) {
            binding.useDefault.isChecked = binding.useDefault.isVisible
        }
    }

    /**
     * Disable all other input elements when the use default checkbox is checked.
     */
    private fun refreshDefaultEnabledState() {
        val isChecked = binding.useDefault.isChecked

        binding.textLayout.isEnabled = !isChecked
        binding.examplesHeader.isEnabled = !isChecked

        // This can never fail because RadioButtons only work when they are the immediate
        // children of a RadioGroups
        binding.examplesGroup.children.forEach {
            it.isEnabled = !isChecked
        }
    }

    /**
     * Enable the next button if the current inputs are semantically valid.
     *
     * The answer may still be rejected upon submission.
     */
    private fun refreshNextButtonEnabledState() {
        val (_, ok) = getSubmission()

        val dialog = dialog as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = ok
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible =
            viewModel.question.value?.second?.isAuthorize == true
    }

    /**
     * Get the value to submit as the answer to the question.
     *
     * @return The answer computed from the user's current selection/input and whether it is valid.
     * A null answer means omitting it (ie. use the default/current value).
     */
    private fun getSubmission(): Pair<String?, Boolean> {
        val (_, option) = viewModel.question.value ?: return Pair(null, false)

        if (binding.useDefault.isChecked) {
            return if (option.value.isNotEmpty()) {
                Pair(option.value, true)
            } else {
                Pair(option.default, true)
            }
        } else {
            val text = if (option.examples.isEmpty()) {
                binding.text.text.toString()
            } else {
                userInput
            }

            return if (option.exclusive && !option.examples.any { it.value == text }) {
                Pair(text, false)
            } else if (option.required && text.isEmpty()) {
                Pair(text, false)
            } else {
                Pair(text, true)
            }
        }
    }
}