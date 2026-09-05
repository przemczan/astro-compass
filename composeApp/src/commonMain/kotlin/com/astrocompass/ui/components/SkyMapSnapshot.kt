package com.astrocompass.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.DeepSkyObject
import com.astrocompass.catalog.MilkyWayCatalog
import com.astrocompass.catalog.SkyObject
import com.astrocompass.location.ObserverLocation
import com.astrocompass.ui.skymap.MilkyWayCellDirection
import com.astrocompass.ui.skymap.SkyMapDirectionCache
import kotlinx.coroutines.delay

/** Default refresh cadence for [rememberSkyMapSnapshot] -- the sky moves ~15"/s, so this stays
 *  sub-pixel-accurate at any usable zoom for far longer than this; screens that track something
 *  actively moving (a telescope target) may want faster, but nothing needs it as often as every
 *  frame. */
private const val DEFAULT_SNAPSHOT_REFRESH_INTERVAL_MILLIS = 5_000L

/** The "where's everything right now" data [SkyMap] needs: directions for every catalog object
 *  [catalogFilter] admits, the constellation stick figures, the Milky Way density grid, and (for
 *  whichever [DeepSkyObject]s survived the filter) north-offset directions a bundled photo needs
 *  to rotate correctly. [milkyWayGridStepDegrees] rides alongside [milkyWayCells] rather than in
 *  it -- it's the same fixed value for every cell (see [MilkyWayCatalog]), not a per-cell one.
 *  [nowEpochMillis] is exposed too, so a caller that only needs a marker's *current* position (not
 *  sub-second precision -- if it does, e.g. Guidance's live arrow, it should keep its own faster
 *  ticker instead) can position it off this same snapshot rather than running a second ticker. */
data class SkyMapSnapshot(
    val directions: List<Pair<SkyObject, Vector3>>,
    val constellationLines: List<List<Vector3>>,
    val northOffsetDirections: Map<String, Vector3>,
    val milkyWayCells: List<MilkyWayCellDirection>,
    val milkyWayGridStepDegrees: Float,
    val nowEpochMillis: Long,
)

/**
 * Builds and periodically refreshes [SkyMapSnapshot] -- the one place that logic lives, shared by
 * every screen that embeds [SkyMap] (Map, Guidance, Alignment). Before this existed, each screen
 * built its own snapshot by hand, and it was easy for that to silently diverge: Map used to
 * pre-filter the catalog by a fixed, user-set magnitude limit before ever reaching [SkyMap]'s own
 * zoom-driven reveal curves, while Guidance never filtered at all -- so the two showed visibly
 * different amounts of the sky at the same zoom, for no reason anyone had decided on purpose. Now
 * the only way two screens' maps can differ is [catalogFilter] and [refreshIntervalMillis], both
 * passed explicitly at the call site -- an intentional, visible choice instead of an accident.
 *
 * Returns an empty snapshot (rather than suspending or erroring) until [CatalogRepository.isLoaded]
 * -- callers that need to gate their own UI on load state (a spinner, say) still collect that flow
 * themselves; this only decides when there's data worth snapshotting.
 *
 * [filterKey] -- not [catalogFilter] itself -- is what [remember] keys the filtered subset on.
 * Lambdas never compare structurally equal across recompositions unless they close over nothing at
 * all, so a [catalogFilter] built from live preference state would otherwise be a *different*
 * lambda instance every recomposition, defeating memoization and re-filtering the whole catalog
 * constantly. Passing the filter's actual backing value (e.g. a
 * [com.astrocompass.catalog.MapObjectFilter], which has real `equals`) as [filterKey] fixes that:
 * [remember] only re-runs [catalogFilter] when that value actually changes. A caller with a truly
 * fixed filter (closing over nothing live) can leave [filterKey] at its `null` default instead.
 */
@Composable
fun rememberSkyMapSnapshot(
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    refreshIntervalMillis: Long = DEFAULT_SNAPSHOT_REFRESH_INTERVAL_MILLIS,
    filterKey: Any? = null,
    catalogFilter: (SkyObject) -> Boolean = { true },
): SkyMapSnapshot {
    val catalogLoaded by catalogRepository.isLoaded.collectAsState()

    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(refreshIntervalMillis) {
        while (true) {
            delay(refreshIntervalMillis)
            now = currentEpochMillis()
        }
    }

    val catalogSubset = remember(catalogLoaded, filterKey) {
        if (catalogLoaded) catalogRepository.all.filter(catalogFilter) else emptyList()
    }
    val directions = remember(catalogSubset, now) {
        SkyMapDirectionCache.build(catalogSubset, location, now)
    }
    val constellationLines = remember(catalogLoaded, now) {
        if (catalogLoaded) {
            SkyMapDirectionCache.buildConstellationDirections(catalogRepository.constellationLines, location, now)
        } else {
            emptyList()
        }
    }
    val northOffsetDirections = remember(catalogSubset, now) {
        SkyMapDirectionCache.northOffsetDirections(catalogSubset.filterIsInstance<DeepSkyObject>(), location, now)
    }
    val milkyWayCells = remember(catalogLoaded, now) {
        if (catalogLoaded) {
            SkyMapDirectionCache.buildMilkyWayDirections(catalogRepository.milkyWay, location, now)
        } else {
            emptyList()
        }
    }

    return SkyMapSnapshot(
        directions, constellationLines, northOffsetDirections,
        milkyWayCells, catalogRepository.milkyWay.gridStepDegrees, now,
    )
}
