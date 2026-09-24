package com.sabreware.aide.ui.custom

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppPage
import com.sabreware.aide.core.designsystem.AppTextField
import com.sabreware.aide.core.designsystem.form.FormActions
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.AutoFocus
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CustomInstructionScreen(
    viewModel: CustomInstructionViewModel = koinViewModel(),
) {
    val nav = navigator()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    AutoFocus(focus, showKeyboard = false)

    fun send() {
        if (viewModel.submit()) nav.goBack()
    }

    AppPage(
        title = "Describe your change",
        trailingActions = listOf(HeaderAction(Res.drawable.ic_lc_send, "Send", onClick = ::send)),
    ) {
        Text(
            "Type a one-shot instruction. The IME will apply it to the current text once and forget it. " +
                "Use your normal keyboard to type.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        )
        AppTextField(
            value = uiState.text,
            onValueChange = viewModel::onTextChange,
            label = "Instruction",
            placeholder = "e.g. \"Make it rhyme.\" or \"Translate to French.\"",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 160.dp)
                .focusRequester(focus),
        )
        FormActions(
            confirmLabel = "Send to Aide IME",
            onConfirm = ::send,
            confirmEnabled = uiState.canSubmit,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}
