package io.gropp.pawparazzi.actions

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.core.detection.Label
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ActionsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun soundPlayerFallsBackToDefaultWhenUriIsInvalid() {
        val player = SoundPlayer(context)
        player.start()
        player.setSource(Label.CAT, "content://invalid.authority/missing.wav")
        assertThat(player.fallbackLabels.value).contains(Label.CAT)
        player.release()
    }

    @Test
    fun soundPlayerDoesNotPlayBeforeLoaded() {
        val player = SoundPlayer(context)
        player.play(Label.DOG)
        player.start()
        player.play(Label.DOG)
        player.release()
    }

    @Test
    fun photoStoreEnforcesCapAndSkipsPending(): Unit = runBlocking {
        val store = PhotoStore(context)
        val resolver = context.contentResolver
        fun insert(name: String, pending: Int): android.net.Uri {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, PhotoNaming.MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, PhotoNaming.RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, pending)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri)!!.use { it.write(ByteArray(1000)) }
            return uri
        }
        val old = insert("cap_test_old.jpg", 0)
        val pending = insert("cap_test_pending.jpg", 1)
        val recent = insert("cap_test_recent.jpg", 0)
        store.enforceCap(1000)
        fun exists(uri: android.net.Uri) = resolver.query(uri, null, null, null, null)?.use { it.count > 0 } ?: false
        assertThat(exists(old)).isFalse()
        assertThat(exists(recent)).isTrue()
        resolver.delete(recent, null, null)
        resolver.delete(pending, null, null)
        Unit
    }
}
