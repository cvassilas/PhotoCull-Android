package com.example.photocull

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.abs

class MainActivity : Activity() {
    private val photos = mutableListOf<Uri>()
    private val decisions = mutableMapOf<Uri, String>()
    private val history = mutableListOf<Pair<Uri, String?>>()
    private var index = 0
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var counter: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
        if (!selected.isNullOrEmpty()) {
            photos.clear()
            photos.addAll(selected)
            decisions.clear()
            history.clear()
            index = 0
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "PhotoCull"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        counter = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f }
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Photo being reviewed. Swipe right to keep, left to mark for deletion."
        }
        status = TextView(this).apply { gravity = Gravity.CENTER; textSize = 18f }
        val choose = Button(this).apply {
            text = "Choose photos"
            setOnClickListener { picker.launch("image/*") }
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        val delete = Button(this).apply { text = "← Mark delete"; setOnClickListener { decide("delete") } }
        val undo = Button(this).apply { text = "Undo"; setOnClickListener { undo() } }
        val keep = Button(this).apply { text = "Keep →"; setOnClickListener { decide("keep") } }
        actions.addView(delete); actions.addView(undo); actions.addView(keep)
        root.addView(title)
        root.addView(counter)
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        root.addView(choose)
        root.addView(actions)
        setContentView(root)

        var downX = 0f
        image.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; true }
                MotionEvent.ACTION_UP -> {
                    val delta = event.x - downX
                    if (abs(delta) > 80) decide(if (delta > 0) "keep" else "delete")
                    true
                }
                else -> true
            }
        }
        render()
    }

    private fun decide(value: String) {
        if (index !in photos.indices) {
            Toast.makeText(this, "Choose photos first or review is complete", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = photos[index]
        history.add(uri to decisions[uri])
        decisions[uri] = value
        index++
        render()
    }

    private fun undo() {
        val previous = history.removeLastOrNull()
        if (previous == null) return
        if (previous.second == null) decisions.remove(previous.first) else decisions[previous.first] = previous.second!!
        index = photos.indexOf(previous.first).coerceAtLeast(0)
        render()
    }

    private fun render() {
        counter.text = if (photos.isEmpty()) "Choose photos to begin" else "Photo ${(index + 1).coerceAtMost(photos.size)} of ${photos.size}"
        val current = photos.getOrNull(index)
        if (current == null) {
            image.setImageDrawable(null)
            status.text = if (photos.isEmpty()) "Swipe right to keep • swipe left to mark for deletion" else "Review complete: ${decisions.values.count { it == "keep" }} kept, ${decisions.values.count { it == "delete" }} marked"
        } else {
            try { contentResolver.openInputStream(current)?.use { image.setImageBitmap(BitmapFactory.decodeStream(it)) } }
            catch (_: Exception) { image.setImageDrawable(null) }
            status.text = "← Mark for deletion        Keep →"
        }
    }
}
