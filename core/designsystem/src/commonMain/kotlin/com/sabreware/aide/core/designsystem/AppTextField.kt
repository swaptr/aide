package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Outlined form field with Material's built-in error affordance. When [errorText] is non-null the
 * field paints its error state (red outline + label) and shows the message in the supporting-text
 * slot — so validation feedback lives on the offending field, not in a separate inline error line.
 * With no error, [helperText] fills the same slot. Always full-width; pass extra sizing via [modifier].
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    helperText: String? = null,
    singleLine: Boolean = false,
    readOnly: Boolean = false,
    placeholder: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val support = errorText ?: helperText
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        readOnly = readOnly,
        isError = errorText != null,
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        supportingText = support?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}
