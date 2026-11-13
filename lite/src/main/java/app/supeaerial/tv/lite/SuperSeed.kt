package app.supeaerial.tv.lite

import android.content.Context
import android.util.Log

/**
 * SuperHome's starting arrangement: the preset folders from [SuperNames], and one folder for the
 * apps that came on the system image.
 *
 * It only ever files apps this pass has not seen before, which is what keeps a person's own
 * arrangement intact: an app you have moved, hidden or put in a folder was seen when it first
 * appeared, so nothing here touches it again. What is new since then — an app installed yesterday,
 * a system app this firmware has and the last one did not — goes where the presets say.
 *
 * The list's `top` packages lead the grid the once they are first seen ([pin]), and are ordinary,
 * movable tiles from then on. [force] is the other way round: it files every app by the list, seen
 * before or not, for when the list itself is what changed — the one-shot row at the bottom of
 * Personalize.
 */
object SuperSeed {
    private const val TAG = "SuperHome"
    private const val PREFS = "super_seed"

    /**
     * Files the fresh apps and returns whether anything moved, so Home lays its tiles out again.
     * [home] is the order on screen, whose last tile is where a new folder goes; [hidden] holds the
     * apps Home does not show, which are left where they are. Off the main thread is not needed:
     * this only writes preferences, and it runs before the tiles go on screen.
     */
    fun place(context: Context, layout: LayoutStore, apps: List<AppEntry>, hidden: Set<String>, home: List<String>): Boolean {
        val seen = seenOf(context)
        // 优先置顶, not 永久置顶: the pinned tiles lead the grid the first time this pass sees them,
        // and are ordinary movable tiles from then on.
        val pinned = pin(context, layout, home)
        val unseen = apps.filter { it.key !in seen && it.key !in hidden }
        if (unseen.isEmpty()) return pinned
        val fresh = unseen.filter { layout.folderHolding(it.key) == null }
        val groups = SuperNames.groups()
        val systemName = context.getString(R.string.folder_system)
        val folderKeys = ArrayList<String>()
        var systemKey: String? = null
        var moved = false
        val going = LinkedHashMap<String, MutableList<String>>()
        for (e in fresh) {
            val group = groups.firstOrNull { e.key in it.packages }
            val name = group?.name ?: if (!e.removable) systemName else continue
            going.getOrPut(name) { ArrayList() } += e.key
        }
        for ((name, keys) in going) {
            // An existing folder of that name takes the apps; a new one starts at the first of them.
            val already = layout.folders().firstOrNull { it.name == name }?.id
            var id: String? = already
            for (key in keys) id = layout.putInFolder(key, id, name)
            // Only a folder started now goes to the end; one already on Home keeps its place there.
            if (already == null) id?.let { folderKeys += FOLDER_KEY_PREFIX + it }
            if (already == null && name == systemName) id?.let { systemKey = FOLDER_KEY_PREFIX + it }
            moved = true
            Log.i(TAG, "preset folder «$name» holds ${keys.size} app(s): ${keys.joinToString(", ")}")
        }
        // A folder made now goes to the end of Home rather than in the middle of the grid where its
        // first app happened to sort, and the system folder goes behind every other one started here.
        val tail = ArrayList(folderKeys.filter { it != systemKey }).apply { systemKey?.let { add(it) } }
        if (home.isNotEmpty()) {
            // Each goes after the tile moved before it: placing them all after the same tile would
            // leave the tail in the reverse of this order.
            var after = home.last()
            for (key in tail) {
                runCatching { layout.move(key, after) }
                after = key
            }
        }
        remember(context, apps, hidden)
        // Home lays its tiles out again when anything moved, even into a folder that was already
        // there. The pass after it has nothing fresh, so this cannot start over.
        return moved || pinned
    }

    /**
     * 优先置顶: the list's `top` packages lead the grid, but only the once each. Their tiles are
     * ordinary ones afterwards, so the owner can move them and the move stands — which is what the
     * display-time sort this replaces could not do. What has been placed is remembered per package,
     * so a package added to the list leads on the pass that first sees it, and never again.
     */
    private fun pin(context: Context, layout: LayoutStore, home: List<String>): Boolean {
        val top = SuperNames.tops()
        if (top.isEmpty()) return false
        val done = pinnedOf(context)
        val todo = top.filter { it in home && it !in done }
        if (todo.isEmpty()) return false
        // The first tile the list does not lead: each of these goes just before it, in the list's own
        // order, so moving them one after the other to the same anchor keeps that order.
        val anchor = home.firstOrNull { it !in top } ?: return false
        for (key in todo) layout.move(key, anchor)
        done.addAll(todo)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("pinned", done).apply()
        Log.i(TAG, "${todo.size} tile(s) placed at the front once: ${todo.joinToString(", ")}")
        return true
    }

    /**
     * 强制生效: the list's folders applied to every app on this TV, seen before or not — what [place]
     * cannot do, since it only ever files apps it has not met. An app already in a folder of the name
     * the list gives it is left where it is; one the list puts elsewhere goes to that folder, made new
     * if this TV has none of that name. Returns how many apps went into a folder. Names need no pass
     * of their own: Home takes them from the list every time it lays the tiles out.
     */
    fun force(context: Context, layout: LayoutStore, apps: List<AppEntry>, hidden: Set<String>): Int {
        val groups = SuperNames.groups()
        val systemName = context.getString(R.string.folder_system)
        var filed = 0
        for (e in apps.filter { it.key !in hidden }) {
            val name = groups.firstOrNull { e.key in it.packages }?.name ?: if (!e.removable) systemName else continue
            if (layout.folderHolding(e.key)?.name == name) continue
            layout.putInFolder(e.key, layout.folders().firstOrNull { it.name == name }?.id, name)
            filed++
        }
        Log.i(TAG, "the list forced over ${apps.size} app(s): $filed filed into folders")
        return filed
    }

    /** The `top` packages already placed at the front, so a later pass leaves the owner's moves alone. */
    private fun pinnedOf(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("pinned", null)?.toMutableSet() ?: HashSet()

    /**
     * The apps this TV had on an earlier pass. Kept across passes rather than replaced: an app that
     * is briefly missing — updating, or a system app toggled off — has to stay remembered, or it
     * would be filed again by its package when it comes back and lose the place on Home it had.
     */
    private fun seenOf(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("seen", null)?.split(',')?.filter { it.isNotEmpty() }?.toHashSet() ?: HashSet()

    /**
     * Every app filed now, plus every app on this TV that is not hidden. A hidden app is left out
     * so that showing it later still puts it in the folder the presets give it.
     */
    private fun remember(context: Context, apps: List<AppEntry>, hidden: Set<String>) {
        val seen = seenOf(context)
        seen.addAll(apps.mapNotNull { it.key.takeUnless { key -> key in hidden } })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("seen", seen.joinToString(",")).apply()
    }
}
