package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.Requirement

/**
 * Every block Masamune's flow plane knows how to place: 418 specs in Automate's 16 categories,
 * ported from `docs/donors/RE-automate.md` and checked field by field against
 * `llamalab.com/automate/doc/block/<id>.html`.
 *
 * The catalog is a `object` holding immutable data, not a registry with a `register()` call,
 * and that is deliberate. A palette that can be extended at run time is a palette whose
 * contents depend on which subsystem happened to initialise first, and flow files reference
 * blocks by [BlockSpec.id] — a block that is sometimes absent turns a saved flow into a file
 * that sometimes fails to open. Everything the editor can place is here, at compile time, or
 * it does not exist.
 *
 * ### Order
 *
 * [all] preserves Automate's own order: categories in [BlockCategory] declaration order, and
 * within each category the donor's own sequence. Rule 0 — port the donor faithfully first. The
 * ordering is part of how the palette *looks*, and "improving" it to alphabetical is exactly
 * the kind of well-meant drift that makes a port impossible to diff against its source.
 */
object BlockCatalog {

    /**
     * All 418 specs, flattened in palette order.
     *
     * The sixteen lists live in sixteen files because one file of 418 specs is a file nobody
     * reviews. They are joined here and nowhere else.
     */
    val all: List<BlockSpec> = buildList(418) {
        addAll(APPS_BLOCKS)
        addAll(BATTERY_AND_POWER_BLOCKS)
        addAll(CAMERA_AND_SOUND_BLOCKS)
        addAll(CONCURRENCY_BLOCKS)
        addAll(CONNECTIVITY_BLOCKS)
        addAll(CONTENT_BLOCKS)
        addAll(DATE_AND_TIME_BLOCKS)
        addAll(STORAGE_BLOCKS)
        addAll(FLOW_BLOCKS)
        addAll(GENERAL_BLOCKS)
        addAll(INTERFACE_BLOCKS)
        addAll(LOCATION_BLOCKS)
        addAll(MESSAGING_BLOCKS)
        addAll(SENSOR_BLOCKS)
        addAll(SETTINGS_BLOCKS)
        addAll(TELEPHONY_BLOCKS)
    }

    /**
     * Lookup by [BlockSpec.id], which is the key a saved flow stores.
     *
     * Built with an explicit duplicate check rather than `associateBy`, which silently keeps
     * the last of a colliding pair. A duplicate id is not a cosmetic mistake: whichever spec
     * lost the collision becomes unreachable, and every node in every saved flow that named it
     * quietly resolves to the wrong block. Failing at class-load is the cheap version of that
     * bug.
     */
    private val index: Map<String, BlockSpec> = HashMap<String, BlockSpec>(all.size * 2).apply {
        for (spec in all) {
            val clash = put(spec.id, spec)
            require(clash == null) {
                "Duplicate block id '${spec.id}': ${clash?.name} and ${spec.name}"
            }
        }
    }

    /**
     * Specs grouped by category, in [BlockCategory] declaration order and donor order within
     * each group — the palette renders this map directly.
     */
    val byCategory: Map<BlockCategory, List<BlockSpec>> =
        BlockCategory.entries.associateWithTo(LinkedHashMap()) { category ->
            all.filter { it.category == category }
        }

    val size: Int get() = all.size

    /** The spec for [id], or null if no such block exists. */
    operator fun get(id: String): BlockSpec? = index[id]

    /**
     * The spec for [id], or a thrown [IllegalArgumentException].
     *
     * For the loader, which has already decided that an unknown id means a corrupt or
     * forward-versioned flow file and wants to say so with the id in hand.
     */
    fun require(id: String): BlockSpec =
        index[id] ?: throw IllegalArgumentException("Unknown block id: $id")

    fun of(category: BlockCategory): List<BlockSpec> = byCategory[category].orEmpty()

    /**
     * Palette search over display name, id and summary.
     *
     * Ranked, not merely filtered: with 418 blocks and names sharing long prefixes
     * (`App notifications priority get` / `…priority set` / `…visibility get`), an unranked
     * substring match buries the block the user typed the first word of. Name-prefix matches
     * come first, then any name match, then id, then summary — and the donor's own order
     * breaks ties, so results are stable between keystrokes.
     */
    fun search(query: String): List<BlockSpec> {
        val q = query.trim()
        if (q.isEmpty()) return all
        fun rank(spec: BlockSpec): Int = when {
            spec.name.startsWith(q, ignoreCase = true) -> 0
            spec.name.contains(q, ignoreCase = true) -> 1
            spec.id.contains(q, ignoreCase = true) -> 2
            spec.summary.contains(q, ignoreCase = true) -> 3
            else -> 4
        }
        return all.map { it to rank(it) }
            .filter { it.second < 4 }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * What [spec] still needs, given the grants the device currently has.
     *
     * The palette shows the *missing* requirements rather than a bare disabled state because
     * the three service grants live in three different Settings screens, and "needs a
     * permission" without saying which one is a dead end for the user. An empty result means
     * the block is placeable.
     */
    fun missingRequirements(spec: BlockSpec, satisfied: Set<Requirement>): Set<Requirement> =
        spec.requires - satisfied

    /** Whether [spec] may be placed, given [satisfied]. */
    fun isPlaceable(spec: BlockSpec, satisfied: Set<Requirement>): Boolean =
        spec.requires.all { it in satisfied }

    /** Every distinct requirement the catalog can ask for — what the capability screen lists. */
    val allRequirements: Set<Requirement> =
        all.flatMapTo(LinkedHashSet()) { it.requires }

    /**
     * Blocks that can never run in the current environment, with the reason.
     *
     * Distinct from filtering the palette: the editor also has to explain an *existing* flow
     * that will not run, and it needs the per-node reason to do it.
     */
    fun blockedBy(satisfied: Set<Requirement>): Map<BlockSpec, Set<Requirement>> =
        all.mapNotNull { spec ->
            val missing = missingRequirements(spec, satisfied)
            if (missing.isEmpty()) null else spec to missing
        }.toMap()

    /** Specs of a given shape — mostly a convenience for the editor's connector rendering. */
    fun ofShape(shape: BlockShape): List<BlockSpec> = all.filter { it.shape == shape }
}
