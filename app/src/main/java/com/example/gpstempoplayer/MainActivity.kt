package com.example.gpstempoplayer

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val bg = Color.rgb(7, 9, 22)
    private val panel = Color.rgb(17, 22, 47)
    private val panel2 = Color.rgb(24, 29, 60)
    private val purple = Color.rgb(159, 73, 255)
    private val pink = Color.rgb(228, 72, 255)
    private val white = Color.rgb(246, 243, 255)
    private val muted = Color.rgb(169, 166, 202)

    private lateinit var player: ExoPlayer
    private lateinit var locationManager: LocationManager
    private lateinit var speedText: TextView
    private lateinit var rateText: TextView
    private lateinit var gpsText: TextView
    private lateinit var trackText: TextView
    private lateinit var playButton: Button
    private lateinit var progress: SeekBar
    private lateinit var playlistBox: LinearLayout
    private lateinit var statusText: TextView
    private val tracks = mutableListOf<Pair<Uri, String>>()
    private var speedKmh = 0f
    private var filteredSpeed = 0f
    private var minRate = 0.75f
    private var maxRate = 1.75f
    private var startSpeed = 10f
    private var maxSpeed = 100f
    private var smoothingSeconds = 3
    private var gpsSensitivity = 0.55f
    private var gpsEnabled = true
    private var selectedIndex = 0
    private var userSeeking = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val name = queryName(uri)
            if (tracks.none { it.first == uri }) tracks.add(uri to name)
        }
        refreshPlaylist()
        if (player.mediaItemCount == 0 && tracks.isNotEmpty()) loadTrack(0, false)
        statusText.text = "${tracks.size} فایل در کتابخانه"
    }

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startGps()
        else statusText.text = "برای کنترل سرعت، مجوز GPS لازم است."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        buildUi()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playButton.text = if (isPlaying) "Ⅱ  مکث" else "▶  پخش" }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                selectedIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                refreshPlaylist()
                updateTrackLabel()
            }
        })
        requestGpsIfNeeded()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(12), dp(18), dp(14))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val brand = TextView(this).apply { text = "♫  GPS TEMPO"; textSize = 20f; setTextColor(white); typeface = Typeface.DEFAULT_BOLD }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = Button(this).apply { text = "⚙"; setTextColor(white); setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel2)); setOnClickListener { showSettings() } }
        header.addView(settings)
        content.addView(header)
        content.addView(subtitle("Music moves with you", 12f), margin(0, 0, 0, 18))

        val hero = card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(16))
            addView(subtitle("سرعت فعلی GPS", 13f))
            speedText = TextView(this@MainActivity).apply { text = "—"; textSize = 58f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white); gravity = Gravity.CENTER }
            addView(speedText)
            addView(subtitle("km/h", 13f))
            val rateRow = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
            rateText = TextView(this@MainActivity).apply { text = "1.00×"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(pink) }
            rateRow.addView(rateText)
            rateRow.addView(subtitle("  سرعت پخش", 13f))
            addView(rateRow)
            gpsText = subtitle("●  منتظر دریافت GPS", 12f)
            gpsText.setPadding(0, dp(12), 0, 0)
            addView(gpsText)
        })
        content.addView(hero, margin(0, 0, 0, 14))

        val trackCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(subtitle("اکنون در حال پخش", 12f))
            trackText = TextView(this@MainActivity).apply { text = "هنوز فایلی انتخاب نشده"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white); setPadding(0, dp(7), 0, dp(12)) }
            addView(trackText)
            progress = SeekBar(this@MainActivity).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(pink)
                thumbTintList = android.content.res.ColorStateList.valueOf(white)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        userSeeking = false
                        if (player.duration > 0) player.seekTo((player.duration * ((seekBar?.progress ?: 0) / 1000f)).toLong())
                    }
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                })
            }
            addView(progress)
            val controls = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
            val prev = smallButton("⏮") { if (player.mediaItemCount > 0) player.seekToPreviousMediaItem() }
            controls.addView(prev)
            playButton = Button(this@MainActivity).apply {
                text = "▶  پخش"; textSize = 16f; setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(purple))
                setOnClickListener {
                    if (player.mediaItemCount == 0) pickAudio.launch(arrayOf("audio/*"))
                    else if (player.isPlaying) player.pause() else player.play()
                }
            }
            controls.addView(playButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(12), 0, dp(12), 0) })
            controls.addView(smallButton("⏭") { if (player.mediaItemCount > 0) player.seekToNextMediaItem() })
            addView(controls)
        }
        content.addView(card(trackCard), margin(0, 0, 0, 14))

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(actionTile("＋", "افزودن موسیقی") { pickAudio.launch(arrayOf("audio/*")) }, LinearLayout.LayoutParams(0, dp(86), 1f).apply { setMargins(0, 0, dp(6), 0) })
        quick.addView(actionTile("☷", "لیست پخش") { showPlaylistDialog() }, LinearLayout.LayoutParams(0, dp(86), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        content.addView(quick, margin(0, 0, 0, 14))

        val config = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(title("کنترل سریع"))
            addView(subtitle("حداقل سرعت پخش", 12f))
            addView(slider(50, 150) { value -> minRate = value / 100f; if (maxRate < minRate) maxRate = minRate; updateRate() })
            addView(subtitle("حداکثر سرعت پخش", 12f))
            addView(slider(100, 250) { value -> maxRate = max(minRate, value / 100f); updateRate() })
            addView(subtitle("سرعت GPS برای رسیدن به حداکثر پخش", 12f))
            addView(slider(20, 180) { value -> maxSpeed = value.toFloat(); updateRate() })
            addView(subtitle("تنظیمات دقیق‌تر در ⚙", 11f).apply { setTextColor(purple) })
        }
        content.addView(card(config), margin(0, 0, 0, 12))
        statusText = subtitle("آماده", 12f)
        statusText.gravity = Gravity.CENTER
        content.addView(statusText, margin(0, 4, 0, 8))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            addView(bottomItem("♫", "موسیقی") { pickAudio.launch(arrayOf("audio/*")) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bottomItem("◉", "GPS") { requestGpsIfNeeded() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bottomItem("⚙", "تنظیمات") { showSettings() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bottom)
        setContentView(root)
        startProgressUpdates()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        fun label(text: String) { box.addView(TextView(this).apply { this.text = text; textSize = 13f; setTextColor(white); setPadding(0, dp(9), 0, 0) }) }
        label("شروع افزایش سرعت GPS (km/h)")
        val start = SeekBar(this).apply { max = 60; progress = startSpeed.toInt(); progressTintList = android.content.res.ColorStateList.valueOf(purple) }
        box.addView(start)
        label("زمان نرم‌شدن تغییر سرعت")
        val smooth = SeekBar(this).apply { max = 10; progress = smoothingSeconds; progressTintList = android.content.res.ColorStateList.valueOf(purple) }
        box.addView(smooth)
        label("حساسیت GPS")
        val sensitivity = SeekBar(this).apply { max = 100; progress = (gpsSensitivity * 100).toInt(); progressTintList = android.content.res.ColorStateList.valueOf(purple) }
        box.addView(sensitivity)
        val filter = Switch(this).apply { text = "فیلتر نرم‌سازی GPS"; setTextColor(white); isChecked = gpsEnabled; buttonTintList = android.content.res.ColorStateList.valueOf(purple) }
        box.addView(filter)
        AlertDialog.Builder(this).setTitle("تنظیمات پیشرفته").setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                startSpeed = start.progress.toFloat()
                smoothingSeconds = smooth.progress
                gpsSensitivity = sensitivity.progress / 100f
                gpsEnabled = filter.isChecked
                updateRate()
            }.setNegativeButton("انصراف", null).show().also { dialog ->
                dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)
            }
    }

    private fun requestGpsIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else startGps()
    }

    private fun startGps() {
        try {
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) { gpsText.text = "GPS خاموش است"; return }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
                    filteredSpeed = if (!gpsEnabled) speedKmh else {
                        val alpha = (gpsSensitivity * (1f / (smoothingSeconds + 1f))).coerceIn(0.08f, 1f)
                        if (filteredSpeed == 0f) speedKmh else filteredSpeed + alpha * (speedKmh - filteredSpeed)
                    }
                    speedText.text = String.format(Locale.getDefault(), "%.0f", filteredSpeed)
                    gpsText.text = "●  GPS فعال  ·  ${String.format(Locale.getDefault(), "%.1f", location.accuracy)}m دقت"
                    updateRate()
                }
                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) { gpsText.text = "GPS غیرفعال است" }
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                gpsText.text = "●  در انتظار سیگنال GPS"
            }
        } catch (e: Exception) { gpsText.text = "خطا در دریافت GPS" }
    }

    private fun updateRate() {
        val span = (maxSpeed - startSpeed).coerceAtLeast(1f)
        val t = ((filteredSpeed - startSpeed) / span).coerceIn(0f, 1f)
        val target = minRate + (maxRate - minRate) * t
        val rate = if (filteredSpeed < startSpeed) minRate else target
        rateText.text = String.format(Locale.getDefault(), "%.2f×", rate)
        if (::player.isInitialized && player.mediaItemCount > 0) {
            try { player.playbackParameters = PlaybackParameters(rate.coerceIn(0.25f, 3f)) } catch (_: Exception) {}
        }
    }

    private fun loadTrack(index: Int, autoplay: Boolean = true) {
        if (tracks.isEmpty()) return
        selectedIndex = index.coerceIn(0, tracks.lastIndex)
        player.setMediaItems(tracks.map { MediaItem.fromUri(it.first) }, selectedIndex, 0L)
        player.prepare()
        if (autoplay) player.play()
        updateTrackLabel()
        refreshPlaylist()
        statusText.text = "فایل ${selectedIndex + 1} از ${tracks.size}"
    }

    private fun updateTrackLabel() {
        trackText.text = tracks.getOrNull(selectedIndex)?.second ?: "هنوز فایلی انتخاب نشده"
    }

    private fun refreshPlaylist() {
        if (!::playlistBox.isInitialized) return
        playlistBox.removeAllViews()
        tracks.forEachIndexed { index, item ->
            playlistBox.addView(TextView(this).apply {
                text = (if (index == selectedIndex) "▶  " else "♫  ") + item.second
                textSize = 14f
                setTextColor(if (index == selectedIndex) pink else white)
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { loadTrack(index) }
            })
        }
    }

    private fun showPlaylistDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        playlistBox = box
        refreshPlaylist()
        if (tracks.isEmpty()) box.addView(subtitle("هنوز موسیقی اضافه نشده است.", 14f))
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("لیست پخش").setView(scroll)
            .setPositiveButton("افزودن فایل") { _, _ -> pickAudio.launch(arrayOf("audio/*")) }
            .setNegativeButton("بستن", null).show()
    }

    private fun queryName(uri: Uri): String {
        var result = uri.lastPathSegment ?: "Audio"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) result = c.getString(i) ?: result
            }
        } catch (_: Exception) {}
        return result.substringAfterLast('/')
    }

    private fun startProgressUpdates() {
        val handler = android.os.Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (::player.isInitialized && player.duration > 0 && !userSeeking) {
                    progress.progress = ((player.currentPosition.toFloat() / player.duration) * 1000).toInt().coerceIn(0, 1000)
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(tick)
    }

    private fun card(view: View): View {
        view.setBackgroundColor(panel)
        view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(panel); cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.rgb(43, 40, 79))
        }
        return view
    }
    private fun subtitle(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setTextColor(muted) }
    private fun title(text: String) = TextView(this).apply { this.text = text; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white); setPadding(0, 0, 0, dp(8)) }
    private fun smallButton(text: String, click: () -> Unit) = Button(this).apply { this.text = text; textSize = 17f; setTextColor(white); setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel2)); setOnClickListener { click() } }
    private fun actionTile(icon: String, label: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(panel); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(43, 40, 79)) }
        addView(TextView(this@MainActivity).apply { text = icon; textSize = 24f; setTextColor(pink) })
        addView(TextView(this@MainActivity).apply { text = label; textSize = 12f; setTextColor(white) })
        setOnClickListener { click() }
    }
    private fun bottomItem(icon: String, label: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(3))
        addView(TextView(this@MainActivity).apply { text = icon; textSize = 20f; setTextColor(pink); gravity = Gravity.CENTER })
        addView(TextView(this@MainActivity).apply { text = label; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER })
        setOnClickListener { click() }
    }
    private fun slider(initial: Int, maxValue: Int, changed: (Int) -> Unit) = SeekBar(this).apply {
        max = maxValue; progress = initial; progressTintList = android.content.res.ColorStateList.valueOf(purple)
        thumbTintList = android.content.res.ColorStateList.valueOf(pink)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { changed(value) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    private fun margin(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
