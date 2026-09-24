package com.sabreware.aide.ui.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.domain.label.LabelStore
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.label.labels
import com.sabreware.aide.core.domain.label.labelsNow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * The user's names, tags and pins — for ANY subject. One ViewModel every surface shares, so renaming a model
 * from its row, its detail page or the chat sheet is the same call, and a tag created anywhere is offered
 * everywhere.
 */
class LabelsViewModel(private val store: LabelStore) : ViewModel() {

    val labels: StateFlow<Labels> =
        store.labels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.labelsNow)

    /** Gives [subject] the name [alias]; blank, or the thing's own [originalName], clears it. */
    fun rename(subject: LabelSubject, alias: String, originalName: String?) = edit { it.rename(subject, alias, originalName) }

    fun setTags(subject: LabelSubject, tags: List<String>) = edit { it.setTags(subject, tags) }

    /** Adds [tag] to every subject, or — when every one already has it — removes it from all of them. */
    fun toggleTag(subjects: List<LabelSubject>, tag: String) = edit { labels ->
        fun has(subject: LabelSubject) = labels[subject].tags.any { it.equals(tag, ignoreCase = true) }
        val remove = subjects.isNotEmpty() && subjects.all(::has)
        subjects.fold(labels) { acc, subject ->
            val current = acc[subject].tags
            acc.setTags(subject, if (remove) current.filterNot { it.equals(tag, ignoreCase = true) } else current + tag)
        }
    }

    fun setPinned(subjects: List<LabelSubject>, pinned: Boolean) = edit { labels ->
        val now = Clock.System.now().toEpochMilliseconds()
        subjects.foldIndexed(labels) { i, acc, subject -> acc.setPinned(subject, pinned, now + i) }
    }

    fun createTag(name: String) = edit { it.createTag(name) }

    /** Drops the labels of subjects that no longer exist (a deleted chat, a removed model). */
    fun forget(subjects: List<LabelSubject>) {
        val gone = subjects.mapTo(HashSet()) { it.key }
        edit { labels -> labels.without { it.key in gone } }
    }

    fun renameTag(from: String, to: String) = edit { it.renameTag(from, to) }

    /** Deletes every tag in [names] from the vocabulary and from everything that carried it, in one write. */
    fun deleteTags(names: List<String>) = edit { labels -> names.fold(labels) { acc, name -> acc.deleteTag(name) } }

    // Against the file, never the replay value above: two quick edits must both land.
    private fun edit(transform: (Labels) -> Labels) {
        viewModelScope.launch { store.update(transform) }
    }
}
