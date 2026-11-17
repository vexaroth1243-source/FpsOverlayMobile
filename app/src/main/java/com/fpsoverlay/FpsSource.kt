package com.fpsoverlay

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock

data class FpsResult(val fps: Double, val frameMs: Double, val low1Fps: Double?, val process: String)

/**
 * Önde çalışan uygulamanın (oyunun) FPS'i. SurfaceFlinger'ın kare zaman damgaları okunur:
 *   dumpsys SurfaceFlinger --latency <katman>
 * Bunun için iki yol var:
 *  1) DUMP izni (bilgisayardan bir kerelik: adb shell pm grant com.fpsoverlay android.permission.DUMP)
 *  2) Root (su)
 * İkisi de yoksa FPS "yok" kalır; sahte değer üretilmez.
 */
class FpsSource(private val ctx: Context, private val s: Settings) {
    var available = false
        private set
    var note: String? = null
        private set

    private var mode = "yok"
    private var pkg: String? = null
    private var label: String? = null
    private var candidates: List<String> = emptyList()
    private var candIdx = 0
    private var layerAt = 0L

    private val times = ArrayList<Double>()   // ms, CLOCK_MONOTONIC
    private var lastTs = 0.0

    // Tanılama (sensör raporuna yazılır)
    private var dbgFocus = ""
    private var dbgLayers = ""
    private var dbgLatency = ""

    private val focusRx = Regex("\\su\\d+\\s([A-Za-z0-9_.]+)/")

    private fun hasDump() =
        ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    private fun pickMode(): Boolean {
        mode = when {
            hasDump() -> "adb"
            s.useRoot && Sys.hasRoot() -> "root"
            else -> "yok"
        }
        return mode != "yok"
    }

    private fun run(cmd: String, timeoutMs: Long): String? = when (mode) {
        "adb" -> Sys.sh(cmd, timeoutMs)
        "root" -> Sys.su(cmd, timeoutMs)
        else -> null
    }

    fun sample(): FpsResult? {
        if (!pickMode()) {
            available = false
            note = if (s.useRoot) "Root alınamadı. FPS için adb izni ver (Katman → FPS kurulumu)"
            else "FPS için bir kerelik izin gerekli (Katman → FPS kurulumu)"
            return null
        }
        available = true
        note = null

        val now = SystemClock.elapsedRealtime()
        if (candidates.isEmpty() || now - layerAt > 3000) resolveLayers(now)
        val p = pkg ?: return null
        if (candidates.isEmpty()) return null

        val nowMs = System.nanoTime() / 1_000_000.0
        var frames = latestFrames(candidates[candIdx])
        val fresh = { f: List<Double>? -> f != null && f.isNotEmpty() && nowMs - f.last() < 2500 }

        if (!fresh(frames)) {
            // Seçili katman ölü: diğer adayları dene, canlı olanı seç
            for (i in candidates.indices) {
                if (i == candIdx) continue
                val f = latestFrames(candidates[i])
                if (fresh(f)) {
                    candIdx = i
                    frames = f
                    times.clear(); lastTs = 0.0
                    break
                }
            }
        }
        val fr = frames
        if (!fresh(fr) || fr == null) return null

        for (ms in fr) if (ms > lastTs) { times.add(ms); lastTs = ms }
        val keepFrom = lastTs - 10_000
        while (times.isNotEmpty() && times[0] < keepFrom) times.removeAt(0)

        val win = times.filter { it > lastTs - 1000 }
        if (win.size < 2) return null
        val span = win.last() - win.first()
        if (span <= 0) return null
        val fps = (win.size - 1) / span * 1000.0
        val frameMs = span / (win.size - 1)

        var low1: Double? = null
        if (times.size >= 20) {
            val deltas = times.zipWithNext { a, b -> b - a }.sorted()
            val p99 = deltas[((deltas.size - 1) * 0.99).toInt()]
            if (p99 > 0) low1 = 1000.0 / p99
        }
        return FpsResult(fps, frameMs, low1, label ?: p)
    }

    /** Katmanın geçerli kare zaman damgaları (ms, artan). Okunamazsa null. */
    private fun latestFrames(layer: String): List<Double>? {
        val out = run("dumpsys SurfaceFlinger --latency '${layer.replace("'", "")}'", 2500) ?: return null
        dbgLatency = out.lineSequence().take(4).joinToString(" | ").take(300)
        val res = ArrayList<Double>()
        for (ln in out.lineSequence().drop(1)) {
            val parts = ln.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val t = parts[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue   // geçersiz kare
            res.add(t / 1_000_000.0)
        }
        res.sort()
        return res
    }

    private fun resolveLayers(now: Long) {
        layerAt = now
        val out = run(
            "dumpsys window | grep mCurrentFocus; " +
                "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'; " +
                "echo @@LAYERS; dumpsys SurfaceFlinger --list", 6000
        )
        if (out == null) { candidates = emptyList(); dbgFocus = "komut çıktısı alınamadı"; return }

        val idx = out.indexOf("@@LAYERS")
        val focus = if (idx >= 0) out.substring(0, idx) else out
        val layerText = if (idx >= 0) out.substring(idx + 8) else ""
        dbgFocus = focus.trim().take(300).ifEmpty { "boş" }

        val p = focusRx.findAll(focus).map { it.groupValues[1] }
            .firstOrNull { it != "com.android.systemui" }
        if (p == null || p == ctx.packageName) {
            pkg = null; candidates = emptyList(); label = null
            times.clear(); lastTs = 0.0
            dbgLayers = "ölçülecek uygulama yok (önde: ${p ?: "bulunamadı"})"
            return
        }
        if (p != pkg) {
            times.clear(); lastTs = 0.0; candIdx = 0
            pkg = p
            label = try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString()
            } catch (e: Exception) { p }
        }

        val names = layerText.lineSequence().map { it.trim() }
            .filter { it.contains(p) && !it.startsWith("Background") }.toList()
        val prev = candidates.getOrNull(candIdx)
        val list = (names.filter { it.contains("SurfaceView") } + names).distinct().take(5)
        candidates = list
        candIdx = if (prev != null && prev in list) list.indexOf(prev) else 0
        dbgLayers = "paket=$p, ${list.size} aday: " + list.joinToString(" ; ")
    }

    fun diag(): String = buildString {
        appendLine("FPS yöntemi: " + when (mode) { "adb" -> "DUMP izni"; "root" -> "root"; else -> "yok" })
        appendLine("DUMP izni: " + if (hasDump()) "verilmiş" else "verilmemiş")
        appendLine("Odak çıktısı: $dbgFocus")
        appendLine("Katmanlar: $dbgLayers")
        appendLine("Son --latency çıktısı (ilk satırlar): $dbgLatency")
    }
}
