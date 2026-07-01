package com.uglyos.launcher

import android.content.Context

/*
 * Frecency ("frequency" + "recency") tracking for anything the user selects in
 * search — apps, contacts, settings. Each use bumps a per-key score that decays
 * with age, so things you pick often and recently rank higher. The score feeds
 * ranking as a bounded bonus: it reorders comparable matches without letting a
 * favourite jump a tighter match into a lower tier.
 *
 * Keys are namespaced by their caller (e.g. "app:com.foo", "contact:<lookup>")
 * so the three sources share one store without colliding. We don't keep every
 * use time — just a running score and when it was last touched, decaying it
 * forward on each read/write, the standard trick for compressing a use history
 * into a single number.
 */
object Frecency {
    private const val PREFS = "frecency"
    private const val SCORE = "_score"
    private const val TIME = "_time"

    /** Score halves after this long untouched. */
    private const val HALF_LIFE_MS = 3.0 * 24 * 60 * 60 * 1000

    private fun decay(dtMs: Long): Double = Math.pow(0.5, dtMs / HALF_LIFE_MS)

    /** Record that [key] was just used. */
    fun record(context: Context, key: String, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(key + TIME, now)
        val decayed = prefs.getFloat(key + SCORE, 0f) * decay(now - last)
        prefs.edit()
            .putFloat(key + SCORE, (decayed + 1.0).toFloat())
            .putLong(key + TIME, now)
            .apply()
    }

    /** Current decayed score for every tracked key (empties omitted). */
    fun scores(context: Context, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = HashMap<String, Double>()
        for ((k, value) in prefs.all) {
            if (!k.endsWith(SCORE)) continue
            val key = k.removeSuffix(SCORE)
            val stored = (value as? Float) ?: continue
            val last = prefs.getLong(key + TIME, now)
            val score = stored * decay(now - last)
            if (score > 0.0) out[key] = score
        }
        return out
    }

    /**
     * Bounded, log-scaled boost for a raw frecency [score]. Log-scaled so heavy
     * use can't run away; capped below one match-tier gap so it only reorders
     * comparable matches, never crosses a tier.
     */
    fun boost(score: Double?): Int =
        if (score == null) 0 else (Math.log1p(score) * 15).toInt().coerceAtMost(40)
}
