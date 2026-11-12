package com.meltgap.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rectangle in normalized image coordinates (0..1 of the analysis frame, unrotated buffer).
 */
data class Zone(
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float
) {
    fun copyZone() = Zone(left, top, right, bottom)

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clampInside() {
        left = left.coerceIn(0f, 0.97f)
        top = top.coerceIn(0f, 0.97f)
        right = right.coerceIn(left + 0.02f, 1f)
        bottom = bottom.coerceIn(top + 0.02f, 1f)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("l", left.toDouble())
        .put("t", top.toDouble())
        .put("r", right.toDouble())
        .put("b", bottom.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = Zone(
            o.optDouble("l", 0.1).toFloat(),
            o.optDouble("t", 0.3).toFloat(),
            o.optDouble("r", 0.3).toFloat(),
            o.optDouble("b", 0.7).toFloat()
        )
    }
}

data class NodeConfig(
    var name: String,
    var nozzle: Zone,
    var jetAnchorX: Float,
    var jetHalfWidth: Float
)

/**
 * Global prototype settings. Persisted in SharedPreferences as JSON.
 */
class AppSettings private constructor(private val context: Context) {

    var nodes: MutableList<NodeConfig> = defaultNodes()
    /** Real width of the whole camera frame in mm. 0 = not calibrated yet. */
    var mmPerImageWidth: Float = 0f
    var targetGapMm: Float = 5.0f
    var toleranceMm: Float = 0.6f
    var autoCalOk: Boolean = false
    var calibrationSamples: Int = 0

    fun resetZones() {
        nodes = defaultNodes()
        autoCalOk = false
        save()
    }

    fun mmPerPixel(frameWidth: Int): Float =
        if (mmPerImageWidth <= 0f || frameWidth <= 0) 0f else mmPerImageWidth / frameWidth

    fun isScaleCalibrated(): Boolean = mmPerImageWidth > 0f

    fun save() {
        val root = JSONObject()
        root.put("mmPerImageWidth", mmPerImageWidth.toDouble())
        root.put("targetGapMm", targetGapMm.toDouble())
        root.put("toleranceMm", toleranceMm.toDouble())
        root.put("autoCalOk", autoCalOk)
        root.put("calibrationSamples", calibrationSamples)

        val arr = JSONArray()
        for (n in nodes) {
            val o = JSONObject()
            o.put("name", n.name)
            o.put("nozzle", n.nozzle.toJson())
            o.put("jetAnchorX", n.jetAnchorX.toDouble())
            o.put("jetHalfWidth", n.jetHalfWidth.toDouble())
            arr.put(o)
        }
        root.put("nodes", arr)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, root.toString())
            .apply()
    }

    private fun load() {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            mmPerImageWidth = root.optDouble("mmPerImageWidth", 0.0).toFloat()
            targetGapMm = root.optDouble("targetGapMm", 5.0).toFloat()
            toleranceMm = root.optDouble("toleranceMm", 0.6).toFloat()
            autoCalOk = root.optBoolean("autoCalOk", false)
            calibrationSamples = root.optInt("calibrationSamples", 0)

            val arr = root.optJSONArray("nodes") ?: return
            if (arr.length() == 0) return
            val loaded = ArrayList<NodeConfig>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                loaded.add(
                    NodeConfig(
                        o.optString("name", "NODE $i"),
                        Zone.fromJson(o.getJSONObject("nozzle")),
                        o.optDouble("jetAnchorX", 0.2).toFloat(),
                        o.optDouble("jetHalfWidth", 0.05).toFloat()
                    )
                )
            }
            if (loaded.isNotEmpty()) nodes = loaded
        }
    }

    companion object {
        private const val PREFS = "meltgap.prefs"
        private const val KEY = "settings"

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also {
                    it.load()
                    instance = it
                }
            }

        fun defaultNodes(): MutableList<NodeConfig> = mutableListOf(
            NodeConfig(
                name = "NODE 1",
                nozzle = Zone(0.06f, 0.28f, 0.30f, 0.72f),
                jetAnchorX = 0.20f,
                jetHalfWidth = 0.06f
            ),
            NodeConfig(
                name = "NODE 2",
                nozzle = Zone(0.56f, 0.28f, 0.80f, 0.72f),
                jetAnchorX = 0.70f,
                jetHalfWidth = 0.06f
            )
        )
    }
}
