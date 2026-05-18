package com.swaptr.aide.ui.custom

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppPage

@Composable
fun CustomInstructionScreen(
    onClose: () -> Unit,
    viewModel: CustomInstructionViewModel = hiltViewModel(),
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun send() {
        if (text.isBlank()) return
        viewModel.submit(text)
        onClose()
    }

    AppPage(
        title = "Describe your change",
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_lc_arrow_left), contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = ::send) {
                Icon(painterResource(R.drawable.ic_lc_send), contentDescription = "Send")
            }
        },
    ) {
        Text(
            "Type a one-shot instruction. The IME will apply it to the current text once and forget it. Use your normal keyboard to type.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("e.g. \"Make it rhyme.\" or \"Translate to French.\"") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 160.dp)
                .focusRequester(focus),
        )
        Button(
            onClick = ::send,
            enabled = text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text("Send to Aide IME")
        }
    }
}
