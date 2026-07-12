package com.xiaoriyue.gpscamera

import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class MediaItem(val uri: Uri, val isVideo: Boolean, val dateAdded: Long)

class GalleryActivity : AppCompatActivity() {

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: MediaAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val grid = findViewById<GridView>(R.id.mediaGrid)
        val emptyText = findViewById<TextView>(R.id.emptyText)

        adapter = MediaAdapter(emptyList())
        grid.adapter = adapter

        grid.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            openMedia(item)
        }

        executor.execute {
            val items = loadMediaItems()
            mainHandler.post {
                adapter.updateItems(items)
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openMedia(item: MediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "找不到可開啟此檔案的應用程式", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 查詢本 App 拍攝、儲存在 Pictures/GpsCamera 與 Movies/GpsCamera 的照片／影片 */
    private fun loadMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        items.addAll(queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/GpsCamera", isVideo = false))
        items.addAll(queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/GpsCamera", isVideo = true))
        return items.sortedByDescending { it.dateAdded }
    }

    private fun queryCollection(collection: Uri, relativePathPrefix: String, isVideo: Boolean): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)

        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("$relativePathPrefix%")
        } else {
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            selectionArgs = arrayOf("%$relativePathPrefix%")
        }

        val cursor: Cursor? = contentResolver.query(
            collection, projection, selection, selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                val date = it.getLong(dateIndex)
                val uri = Uri.withAppendedPath(collection, id.toString())
                result.add(MediaItem(uri, isVideo, date))
            }
        }
        return result
    }

    private fun loadThumbnail(item: MediaItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
            } else if (item.isVideo) {
                @Suppress("DEPRECATION")
                android.media.ThumbnailUtils.createVideoThumbnail(
                    item.uri.toString(), android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                )
            } else {
                contentResolver.openInputStream(item.uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private inner class MediaAdapter(private var items: List<MediaItem>) : BaseAdapter() {

        fun updateItems(newItems: List<MediaItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): MediaItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@GalleryActivity)
                .inflate(R.layout.item_gallery_media, parent, false)

            val thumbnail = view.findViewById<ImageView>(R.id.thumbnail)
            val videoBadge = view.findViewById<TextView>(R.id.videoBadge)
            val item = items[position]

            thumbnail.setImageBitmap(null)
            videoBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            thumbnail.tag = item.uri

            executor.execute {
                val bitmap = loadThumbnail(item)
                mainHandler.post {
                    if (thumbnail.tag == item.uri) {
                        thumbnail.setImageBitmap(bitmap)
                    }
                }
            }

            return view
        }
    }
}
