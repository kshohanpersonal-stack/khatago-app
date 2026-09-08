package com.khatago.finance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import com.khatago.finance.container
import com.khatago.finance.data.db.entity.AttachmentEntity
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen image viewer for an attachment, entirely in-process.
 *
 * There is no image-loading library here because there is nothing to load from: attachments are
 * already downscaled JPEGs inside the app's private directory, so one bounded `BitmapFactory` decode
 * on `Dispatchers.IO` is both simpler and smaller than a dependency that would also want permission
 * to read the whole file. A missing file degrades to a message — a broken record view is worse than
 * an incomplete one.
 */
@Composable
fun AttachmentViewer(
    attachment: AttachmentEntity,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.container
    var bitmap by remember(attachment.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(attachment.id) { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF20E1C17))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            LaunchedEffect(attachment.id) {
                bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        container.attachmentRepository.resolve(attachment)?.let { file ->
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        }
                    }.getOrNull()
                }
                failed = bitmap == null
            }
            when {
                bitmap != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = attachment.originalName ?: "Attachment",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(KhataGoSpacing.lg),
                    )
                    Text(
                        text = attachment.originalName ?: attachment.fileName,
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = KhataGoSpacing.xl),
                    )
                }

                failed -> Text(
                    text = "This image is no longer on the device. The record itself is unaffected — " +
                        "only the attachment is missing.",
                    color = Color.White,
                    modifier = Modifier.padding(KhataGoSpacing.xl),
                )

                else -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.White,
                )
            }
        }
    }
}
