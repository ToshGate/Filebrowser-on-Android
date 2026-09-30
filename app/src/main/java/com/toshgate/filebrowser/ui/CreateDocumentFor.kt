package com.toshgate.filebrowser.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.toshgate.filebrowser.transfer.mimeTypeFor

/**
 * Como ActivityResultContracts.CreateDocument, mas com o MIME escolhido em cada chamada
 * (o contrato do AndroidX fixa-o na construção). O input é o nome sugerido.
 */
class CreateDocumentFor : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeTypeFor(input))
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
