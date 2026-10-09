package io.gropp.pawparazzi.actions

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PhotoStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()

    fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    suspend fun enforceCap(capBytes: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val victims = CapPlanner.selectForDeletion(queryOwnedPhotos(), capBytes)
            for (photo in victims) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot delete ${photo.id}", e)
                }
            }
            if (victims.isNotEmpty()) Log.i(TAG, "Deleted ${victims.size} photos to enforce cap")
        }
    }

    private fun queryOwnedPhotos(): List<StoredPhoto> {
        val photos = mutableListOf<StoredPhoto>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("${PhotoNaming.RELATIVE_PATH}/%", context.packageName),
            "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) photos += StoredPhoto(cursor.getLong(idCol), cursor.getLong(sizeCol))
        }
        return photos
    }

    private companion object {
        const val TAG = "PhotoStore"
    }
}
