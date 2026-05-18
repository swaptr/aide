package com.swaptr.aide.domain.tools.verify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.swaptr.aide.domain.llm.verify.VerificationResult
import com.swaptr.aide.domain.llm.verify.Verifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ContactVerifier(
    private val context: Context,
    private val expectedName: String,
    private val pollIntervalMs: Long = 250,
    private val maxPolls: Int = 6,
) : Verifier<ContactEvidence> {

    override suspend fun verify(): VerificationResult<ContactEvidence> {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return VerificationResult.VerificationImpossible("permission_denied")
        val name = expectedName.trim()
        if (name.isEmpty()) return VerificationResult.NotVerified("empty_name")
        return withContext(Dispatchers.IO) {
            repeat(maxPolls) {
                val hit = lookup(name)
                if (hit != null) return@withContext VerificationResult.Verified(hit)
                delay(pollIntervalMs)
            }
            VerificationResult.NotVerified("no_match")
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
