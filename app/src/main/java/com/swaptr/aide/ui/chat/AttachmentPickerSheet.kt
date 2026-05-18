package com.swaptr.aide.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppSheet

@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
) {
    AppSheet(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        contentSpacing = 0.dp,
    ) { _ ->
        AppMenuList(
            items = listOf(
                AppMenuEntry(
                    title = "Camera",
                    leadingIconRes = R.drawable.ic_lc_camera,
                    onClick = onCamera,
                ),
                AppMenuEntry(
                    title = "Photos",
                    leadingIconRes = R.drawable.ic_lc_image,
                    onClick = onPhotos,
                ),
            ),
        )
    }
}
