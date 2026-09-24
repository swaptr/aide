package com.sabreware.aide.app.tools.verify

import android.content.Context
import android.provider.ContactsContract
import com.sabreware.aide.core.common.di.IO
import com.sabreware.aide.core.domain.llm.verify.VerificationResult
import com.sabreware.aide.core.domain.llm.verify.Verifier
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ContactVerifier(
    private val context: Context,
    private val gate: RuntimePermissionGate,
    private val expectedName: String,
    private val pollIntervalMs: Long = 250,
    private val maxPolls: Int = 6,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Verifier<ContactEvidence> {

    override suspend fun verify(): VerificationResult<ContactEvidence> {
        if (!gate.isGranted(AppPermission.CONTACTS)) {
            return VerificationResult.VerificationImpossible("permission_denied")
        }
        val name = expectedName.trim()
        if (name.isEmpty()) return VerificationResult.NotVerified("empty_name")
        return withContext(ioDispatcher) {
            verifyingQuery {
                repeat(maxPolls) {
                    val hit = lookup(name)
                    if (hit != null) return@verifyingQuery VerificationResult.Verified(hit)
                    delay(pollIntervalMs)
                }
                VerificationResult.NotVerified("no_match")
            }
        }
    }

    private fun lookup(name: String): ContactEvidence? {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
            arrayOf(name),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContactEvidence(contactId = cursor.getLong(0))
            }
        }
        return null
    }
}

data class ContactEvidence(val contactId: Long)
