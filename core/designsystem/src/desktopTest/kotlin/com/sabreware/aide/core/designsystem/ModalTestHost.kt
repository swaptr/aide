package com.sabreware.aide.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** [ComposeUiTest.setContent] under a [ModalHost], as every app root is (AideTheme installs one). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setModalContent(content: @Composable () -> Unit) = setContent { ModalHost(content) }
