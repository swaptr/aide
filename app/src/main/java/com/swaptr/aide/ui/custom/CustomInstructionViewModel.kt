package com.swaptr.aide.ui.custom

import androidx.lifecycle.ViewModel
import com.swaptr.aide.data.custom.CustomInstructionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CustomInstructionViewModel @Inject constructor(
    private val repo: CustomInstructionRepository,
) : ViewModel() {

    fun submit(instruction: String) {
        repo.submit(instruction)
    }
}
