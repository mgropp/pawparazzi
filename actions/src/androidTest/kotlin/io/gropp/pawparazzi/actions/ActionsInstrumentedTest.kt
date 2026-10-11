package io.gropp.pawparazzi.actions

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.core.detection.Label
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class ActionsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val insertedPhotos = mutableListOf<Uri>()
    private val player = SoundPlayer(context)

    @Before
    fun clearLeftovers() {
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("cap_test_%"),
        )
    }

    @After
    fun cleanUp() {
        player.release()
        insertedPhotos.forEach { resolver.delete(it, null, null) }
    }

    @Test
    fun soundPlayerSkipsPlayBeforeLoadedAndPlaysAfter() {
        assertThat(player.play(Label.DOG)).isEqualTo(PlayResult.NOT_LOADED)
        player.start()
        assertThat(player.play(Label.DOG)).isEqualTo(PlayResult.NOT_LOADED)
        player.setSource(Label.DOG, null)
        assertThat(player.play(Label.DOG)).isEqualTo(PlayResult.NOT_LOADED)
        awaitLoaded(Label.DOG)
        assertThat(player.play(Label.DOG)).isEqualTo(PlayResult.PLAYED)
    }

    @Test
    fun soundPlayerFallsBackToDefaultWhenUriIsInvalid() {
        player.start()
        player.setSource(Label.CAT, "content://invalid.authority/missing.wav")
        assertThat(player.fallbackLabels.value).contains(Label.CAT)
    }

    @Test
    fun soundPlayerClearsFallbackLabelWhenSourceIsReset() {
        player.start()
        player.setSource(Label.CAT, "content://invalid.authority/missing.wav")
        assertThat(player.fallbackLabels.value).contains(Label.CAT)
        player.setSource(Label.CAT, null)
        assertThat(player.fallbackLabels.value).doesNotContain(Label.CAT)
    }

    @Test
    fun soundPlayerDoesNotReloadWhenSameUriIsSetTwice() {
        player.start()
        val uri = Uri.fromFile(copyAssetToCache("tone_6s.wav")).toString()
        player.setSource(Label.CAT, uri)
        val loadsAfterFirstSet = player.loadRequestCount
        player.setSource(Label.CAT, uri)
        assertThat(player.loadRequestCount).isEqualTo(loadsAfterFirstSet)
    }

    @Test
    fun soundPlayerDoesNotReloadWhenDefaultIsSetTwice() {
        player.start()
        player.setSource(Label.DOG, null)
        val loadsAfterFirstSet = player.loadRequestCount
        player.setSource(Label.DOG, null)
        assertThat(player.loadRequestCount).isEqualTo(loadsAfterFirstSet)
    }

    @Test
    fun soundCheckAcceptsBundledDefault(): Unit = runBlocking {
        val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.cat_default}")
        assertThat(player.check(uri)).isEqualTo(SoundCheck.Ok)
    }

    @Test
    fun soundCheckRejectsClipLongerThanLimit(): Unit = runBlocking {
        val uri = Uri.fromFile(copyAssetToCache("tone_6s.wav"))
        val result = player.check(uri)
        assertThat(result).isInstanceOf(SoundCheck.TooLong::class.java)
        assertThat((result as SoundCheck.TooLong).durationMs)
            .isGreaterThan(SoundPlayer.MAX_DURATION_MS)
    }

    @Test
    fun soundCheckRejectsNonAudioFile(): Unit = runBlocking {
        val textFile =
            File(context.cacheDir, "not_audio.txt").apply { writeText("definitely not audio") }
        assertThat(player.check(Uri.fromFile(textFile))).isEqualTo(SoundCheck.Unreadable)
    }

    @Test
    fun photoStoreEnforcesCapAndSkipsPending(): Unit = runBlocking {
        val store = PhotoStore(context)
        val old = insertPhoto("cap_test_old.jpg", pending = 0)
        val pending = insertPhoto("cap_test_pending.jpg", pending = 1)
        val recent = insertPhoto("cap_test_recent.jpg", pending = 0)
        store.enforceCap(1000)
        assertThat(exists(old)).isFalse()
        assertThat(exists(pending)).isTrue()
        assertThat(exists(recent)).isTrue()
    }

    @Test
    fun photoStoreNeverDeletesImagesOutsideAppFolder(): Unit = runBlocking {
        val store = PhotoStore(context)
        val outside =
            insertPhoto(
                "cap_test_outside.jpg",
                pending = 0,
                relativePath = "Pictures/PawparazziOutside",
            )
        val inside = insertPhoto("cap_test_inside.jpg", pending = 0)
        store.enforceCap(0)
        assertThat(exists(inside)).isFalse()
        assertThat(exists(outside)).isTrue()
    }

    private fun awaitLoaded(label: Label) {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (!player.isLoaded(label) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertThat(player.isLoaded(label)).isTrue()
    }

    private fun copyAssetToCache(assetName: String): File {
        val target = File(context.cacheDir, assetName)
        instrumentation.context.assets.open(assetName).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun insertPhoto(
        name: String,
        pending: Int,
        relativePath: String = PhotoNaming.RELATIVE_PATH,
    ): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, PhotoNaming.MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, pending)
            }
        val uri =
            checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        insertedPhotos += uri
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(ByteArray(PHOTO_SIZE_BYTES)) }
        return uri
    }

    private fun exists(uri: Uri): Boolean {
        val queryUri =
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentUris.parseId(uri),
            )
        val pendingIncluded = MediaStore.setIncludePending(queryUri)
        return resolver
            .query(pendingIncluded, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
        const val PHOTO_SIZE_BYTES = 1000
    }
}
