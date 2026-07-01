package com.uglyos.launcher

import android.content.Context

/*
 * Frecency ("frequency" + "recency") tracking for launched apps. Each launch
 * bumps a per-package score that decays with age, so apps you open often and
 * recently rank higher in search. The score feeds ranking as a bounded bonus:
 * it reorders comparable matches without letting a favourite non-match jump a
 * tighter one.
 *
 * We don't store every launch time. Instead we keep a running score and the
 * time it was last touched, decaying it forward on each read/write — the
 * standard trick for compressing a visit history into a single number.
 */
object Frecency {
    private const val PREFS = "frecency"
    private const val SCORE = "_score"
    private const val TIME = "_time"

    /** Score halves after this long untouched. */
    private const val HALF_LIFE_MS = 3.0 * 24 * 60 * 60 * 1000

    private fun decay(dtMs: Long): Double = Math.pow(0.5, dtMs / HALF_LIFE_MS)

    /** Record that [packageName] was just launched. */
    fun record(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(packageName + TIME, now)
        val decayed = prefs.getFloat(packageName + SCORE, 0f) * decay(now - last)
        prefs.edit()
            .putFloat(packageName + SCORE, (decayed + 1.0).toFloat())
            .putLong(packageName + TIME, now)
            .apply()
    }

    /** Current decayed score for every tracked package (empties omitted). */
    fun scores(context: Context, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = HashMap<String, Double>()
        for ((key, value) in prefs.all) {
            if (!key.endsWith(SCORE)) continue
            val pkg = key.removeSuffix(SCORE)
            val stored = (value as? Float) ?: continue
            val last = prefs.getLong(pkg + TIME, now)
            val score = stored * decay(now - last)
            if (score > 0.0) out[pkg] = score
        }
        return out
    }
}
