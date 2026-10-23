package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Grava em arquivo o PDF que o WebView geraria na impressão.
 * Fica no pacote android.print porque os callbacks de layout/escrita têm construtor restrito ao pacote.
 */
object PdfPrint {
    fun export(adapter: PrintDocumentAdapter, file: File, onDone: (Boolean) -> Unit) {
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins(400, 400, 400, 400))
            .build()
        adapter.onStart()
        adapter.onLayout(null, attrs, CancellationSignal(), object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val pfd = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                )
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                        pfd.close(); adapter.onFinish(); onDone(true)
                    }

                    override fun onWriteFailed(error: CharSequence?) {
                        pfd.close(); adapter.onFinish(); onDone(false)
                    }
                })
            }

            override fun onLayoutFailed(error: CharSequence?) {
                adapter.onFinish(); onDone(false)
            }
        }, null)
    }
}
