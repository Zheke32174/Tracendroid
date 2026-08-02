package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.ForwardGeocode
import dev.pleiades.masamune.apps.GeocodedAddress
import dev.pleiades.masamune.apps.LocationProvider
import dev.pleiades.masamune.apps.LocationReader
import dev.pleiades.masamune.apps.ReverseGeocode
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Location category's **unprivileged one-shot read/decision** slice — the organ an AI phone
 * operator needs to know where the device is and what that place is called: get the current fix, ask
 * whether a provider is on, ask whether the device is within a radius of a target, and turn a
 * coordinate into an address or an address into a coordinate.
 *
 * ### Why this subset and not the other four
 * Automate's Location category mixes unprivileged one-shot reads with map-UI pickers, a privileged
 * write, a network service call, and an over-time geofence:
 *  - The five that run here — `geocoding_reverse`, `geocoding`, `location_at`, `location_get` and
 *    `location_provider_enabled` — are each a single read or a single computed decision over device
 *    state, expressible through the read-only [LocationReader] seam. `location_at` and `location_get`
 *    carry the `ACCESS_FINE_LOCATION` runtime permission, which is an ordinary (if dangerous) grant the
 *    Location category is predicated on: the honest gate for a missing grant is the seam returning
 *    `null` and the block failing **by name**, not a fabricated fix — so, unlike a `SHELL` tag, the
 *    permission does not keep the block unregistered, it shapes the run-time failure.
 *  - `location_pick` is a map-UI picker, `location_show` launches the map app — both are interactive UI,
 *    not reads. `location_mock` is a privileged *write* that injects a fake fix. `location_provider_set_state`
 *    is a privileged *write* tagged `Requirement.Uid2000` (SHELL). `weather` is a network weather-service
 *    call, not a device read. And the `ON_ENTER`/`ON_EXIT` (geofence) form of `location_at` is an
 *    over-time await, not a one-shot. All six are gated by omission (see [locationLookup]).
 *
 * ### The seam, copied from the Apps, Settings, Battery&Power and Sensor blocks
 * Every device call lives behind the injected [LocationReader] — a narrow, `android.*`-free contract,
 * the exact shape [dev.pleiades.masamune.apps.AppInspector], [dev.pleiades.masamune.apps.SystemSettings],
 * [dev.pleiades.masamune.apps.PowerState] and [dev.pleiades.masamune.apps.SensorReader] give their
 * categories. Two consequences, both deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole
 *     file is unit-testable against a fake on an ordinary JVM — a device is needed to run these, never
 *     to test their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [LocationReader] provider and fails with
 *     [LOCATION_ABSENT] when there is no seam (the app process is not wired in, or it dropped mid-run).
 *     A read that returns `null` becomes a named [Outcome.Fail] ("could not be read") — **never** a
 *     fabricated `0,0` coordinate or a silent NO. A geocode that answers with an explicit no-match
 *     routes NO; a geocode whose backend cannot be reached Fails. A decision whose state cannot be read
 *     Fails rather than routing a misleading NO; a block that cannot read *says so*.
 *
 * ### WATCH / WATCH_VALUE collapse to their one-shot form
 * The catalog marks `location_at`/`location_provider_enabled` WATCH-capable and `location_get`
 * WATCH_VALUE (test/read now, or suspend until it enters/leaves/changes). The watching form needs the
 * monitor subsystem this build does not have, so the one-shot condition — "is the device within the
 * radius *now*", "is the provider on *now*", "what is the fix *now*" — is what runs, which is exactly
 * what a decision or getter in a running flow evaluates. This mirrors the Sensor band decisions in
 * [dev.pleiades.masamune.flow.runtime.impl.ScalarBandSensorBlock] and the Battery&Power reads in
 * [dev.pleiades.masamune.flow.runtime.impl.BatteryLevelBlock].
 *
 * The composition helper [locationLookup] mirrors [sensorLookup], [powerLookup], [settingsLookup] and
 * [appsLookup]: it returns the impls keyed by spec id so a caller composes
 * `locationLookup(provider)[id] ?: base.lookup(id)`.
 */

/** The sentence shown whenever a Location block cannot reach a location seam. Modelled on [SENSOR_ABSENT]. */
internal val LOCATION_ABSENT: String =
    "This location block cannot act: no location seam is available, so Masamune cannot read the " +
        "device's location, providers or geocoder. The seam is wired only inside the Android app " +
        "process; when it is absent the block fails by name rather than reporting a place that never " +
        "was read."

/** The catalog's documented default `radius` for `location_at`: "250 meters". */
private const val DEFAULT_RADIUS_METERS = 250.0

/** Mean Earth radius in metres, for the great-circle distance `location_at` measures. */
private const val EARTH_RADIUS_METERS = 6_371_000.0

// --------------------------------------------------------------------------- shared helpers

/**
 * Great-circle (haversine) distance in metres between two lat/lon coordinates in degrees.
 *
 * A real spherical distance, not a flat Pythagorean approximation: `location_at` compares against a
 * radius that can span far enough for the Earth's curvature to matter, and a flat approximation would
 * mis-route a decision near the poles or across a wide radius. This is the honest "within radius"
 * calculation the honest-gating rule demands — a decision must branch on a real distance, never a guess.
 */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
}

/**
 * A `provider` argument parsed to a [LocationProvider]: [default] when blank/absent, the named provider
 * when recognized, or `null` when a non-blank value names no known provider.
 *
 * The `null` return is the honest "you typed a comparison target I do not recognize", which the caller
 * turns into a visible Fail — exactly as `power_source_plugged` fails on an unrecognized source filter,
 * never silently treating a typo as the default provider.
 */
private fun Value?.asProviderOrDefault(default: LocationProvider): LocationProvider? {
    val text = this.asTextOrNull()?.trim()
    if (text.isNullOrEmpty()) return default
    return when (text.lowercase()) {
        "gps" -> LocationProvider.GPS
        "network" -> LocationProvider.NETWORK
        "passive" -> LocationProvider.PASSIVE
        "balanced", "fused" -> LocationProvider.BALANCED
        else -> null // a non-blank, unrecognized provider — the caller Fails by name
    }
}

// --------------------------------------------------------------------------- geocoding decisions

/**
 * `geocoding_reverse` (Geocoding reverse) — turn a coordinate into an address.
 *
 * DECISION: reads `latitude`/`longitude` as plain numbers, reverse-geocodes through the seam, binds
 * every address field the result carries and routes YES; an explicit no-match routes NO (the coordinate
 * is real but has no address); a geocoder that cannot be reached, or a missing coordinate, Fails by
 * name. A present-but-absent address field is left **unbound**, never a fabricated blank locality.
 */
internal class GeocodingReverseBlock(
    private val locationProvider: () -> LocationReader?,
) : BlockImpl {
    override val specId = "geocoding_reverse"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = locationProvider() ?: return Outcome.Fail(LOCATION_ABSENT)
        val latitude = args["latitude"].asNumOrNull()
        val longitude = args["longitude"].asNumOrNull()
        if (latitude == null || longitude == null) {
            return Outcome.Fail("geocoding_reverse: a latitude and longitude are required to reverse-geocode.")
        }
        val language = args["language"].asTextOrNull()?.takeIf { it.isNotBlank() }
        val result = reader.reverseGeocode(latitude, longitude, language)
            ?: return Outcome.Fail("geocoding_reverse: the geocoder could not be reached.")
        return when (result) {
            is ReverseGeocode.NoMatch -> Outcome.Proceed(Port.NO)
            is ReverseGeocode.Matched -> Outcome.Proceed(Port.YES, addressWrites(node, result.address))
        }
    }

    /** Bind every present address field onto its declared output; an absent field binds nothing. */
    private fun addressWrites(node: FlowNode, address: GeocodedAddress): Map<String, Value> {
        val writes = LinkedHashMap<String, Value>()
        address.locationName?.let { node.outputs["varLocationName"]?.bind(writes, Value.Text(it)) }
        if (address.addressLines.isNotEmpty()) {
            node.outputs["varAddressLines"]?.bind(
                writes,
                Value.ArrayV(address.addressLines.map { Value.Text(it) }),
            )
        }
        address.featureName?.let { node.outputs["varFeatureName"]?.bind(writes, Value.Text(it)) }
        address.thoroughfare?.let { node.outputs["varThoroughfare"]?.bind(writes, Value.Text(it)) }
        address.subThoroughfare?.let { node.outputs["varSubThoroughfare"]?.bind(writes, Value.Text(it)) }
        address.locality?.let { node.outputs["varLocality"]?.bind(writes, Value.Text(it)) }
        address.subLocality?.let { node.outputs["varSubLocality"]?.bind(writes, Value.Text(it)) }
        address.adminArea?.let { node.outputs["varAdminArea"]?.bind(writes, Value.Text(it)) }
        address.subAdminArea?.let { node.outputs["varSubAdminArea"]?.bind(writes, Value.Text(it)) }
        address.postalCode?.let { node.outputs["varPostalCode"]?.bind(writes, Value.Text(it)) }
        address.countryName?.let { node.outputs["varCountryName"]?.bind(writes, Value.Text(it)) }
        address.countryCode?.let { node.outputs["varCountryCode"]?.bind(writes, Value.Text(it)) }
        return writes
    }
}

/**
 * `geocoding` (Geocoding) — turn an address or place name into a coordinate.
 *
 * DECISION: reads `locationName` as text, forward-geocodes through the seam, binds `varDecodedLatitude`
 * and `varDecodedLongitude` and routes YES on a match; an explicit no-match routes NO (the name is real
 * but resolves nowhere); a geocoder that cannot be reached, or a blank name, Fails by name.
 */
internal class GeocodingBlock(
    private val locationProvider: () -> LocationReader?,
) : BlockImpl {
    override val specId = "geocoding"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = locationProvider() ?: return Outcome.Fail(LOCATION_ABSENT)
        val name = args["locationName"].asTextOrNull()?.takeIf { it.isNotBlank() }
            ?: return Outcome.Fail("geocoding: a location name is required to geocode.")
        val language = args["language"].asTextOrNull()?.takeIf { it.isNotBlank() }
        val result = reader.forwardGeocode(name, language)
            ?: return Outcome.Fail("geocoding: the geocoder could not be reached.")
        return when (result) {
            is ForwardGeocode.NoMatch -> Outcome.Proceed(Port.NO)
            is ForwardGeocode.Matched -> {
                val writes = LinkedHashMap<String, Value>()
                node.outputs["varDecodedLatitude"]?.bind(writes, Value.Num(result.latitude))
                node.outputs["varDecodedLongitude"]?.bind(writes, Value.Num(result.longitude))
                Outcome.Proceed(Port.YES, writes)
            }
        }
    }
}

// --------------------------------------------------------------------------- location reads / decisions

/**
 * `location_at` (Location at) — is the device within a radius of a target coordinate right now?
 *
 * DECISION: the one-shot (`IMMEDIATELY`) form of the catalog's WATCH decision — its `ON_ENTER`/`ON_EXIT`
 * geofence form is the over-time await this build's missing monitor subsystem cannot run, so the
 * "is it here *now*" form is what evaluates. It reads the device fix through the seam, computes the real
 * great-circle distance to the target, and routes YES when that distance is within `radius` (default
 * [DEFAULT_RADIUS_METERS]), NO otherwise. A missing target coordinate, or a fix the seam cannot read
 * (no fix / location permission absent), Fails **by name** — never a silent NO from an unreadable
 * location. `responsiveness` is a WATCH-only cadence knob with no meaning for a one-shot test and is
 * ignored.
 *
 * Carries `ACCESS_FINE_LOCATION` in the catalog; that is honored at run by the seam returning `null`
 * when the grant is absent (→ a named Fail), not by leaving the block unregistered — the whole Location
 * category assumes the app may hold location permission.
 */
internal class LocationAtBlock(
    private val locationProvider: () -> LocationReader?,
) : BlockImpl {
    override val specId = "location_at"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = locationProvider() ?: return Outcome.Fail(LOCATION_ABSENT)
        val targetLat = args["latitude"].asNumOrNull()
        val targetLon = args["longitude"].asNumOrNull()
        if (targetLat == null || targetLon == null) {
            return Outcome.Fail("location_at: a target latitude and longitude are required.")
        }
        val radius = args["radius"].asNumOrNull() ?: DEFAULT_RADIUS_METERS
        val fix = reader.currentLocation(LocationProvider.BALANCED, maxFixAgeMillis = null)
            ?: return Outcome.Fail("location_at: the current device location could not be read.")
        val distance = haversineMeters(fix.latitude, fix.longitude, targetLat, targetLon)
        return Outcome.Proceed(if (distance <= radius) Port.YES else Port.NO)
    }
}

/**
 * `location_get` (Location get) — read the current device location fix.
 *
 * ACTION: the one-shot (`IMMEDIATELY`) form of the catalog's WATCH_VALUE getter. It reads the fix for
 * the requested `provider` (default Balanced) through the seam, honoring `maxFixAge` — which the catalog
 * documents as "only used with proceed Maybe immediately", i.e. exactly this form — by passing it to the
 * seam, which drops a stale fix to `null`. It binds `varFixLatitude`/`varFixLongitude` (always present
 * in a real fix) and every optional output the fix carries, then leaves by OK. A fix the seam cannot
 * read (no fix, permission absent, or older than `maxFixAge`) Fails **by name**, never a fabricated
 * origin coordinate. `varFixFlags` is left **unbound** — this minimal seam does not model Automate's fix
 * bitmask, an honest omission rather than a guessed value. `minDistance` is a WATCH_VALUE ("When
 * changed") knob with no meaning for a one-shot read and is ignored. An unrecognized `provider` string
 * Fails by name.
 *
 * Carries `ACCESS_FINE_LOCATION` in the catalog, honored at run by the seam's `null` (→ named Fail), not
 * by omission — see [LocationAtBlock].
 */
internal class LocationGetBlock(
    private val locationProvider: () -> LocationReader?,
) : BlockImpl {
    override val specId = "location_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = locationProvider() ?: return Outcome.Fail(LOCATION_ABSENT)
        val provider = args["provider"].asProviderOrDefault(LocationProvider.BALANCED)
            ?: return Outcome.Fail(
                "location_get: unrecognized location provider (expected gps/network/passive/balanced).",
            )
        val maxFixAge = args["maxFixAge"].asNumOrNull()?.toLong()
        val fix = reader.currentLocation(provider, maxFixAge)
            ?: return Outcome.Fail("location_get: the current device location could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varFixLatitude"]?.bind(writes, Value.Num(fix.latitude))
        node.outputs["varFixLongitude"]?.bind(writes, Value.Num(fix.longitude))
        fix.altitudeMeters?.let { node.outputs["varFixAltitude"]?.bind(writes, Value.Num(it)) }
        fix.bearingDegrees?.let { node.outputs["varFixBearing"]?.bind(writes, Value.Num(it)) }
        fix.speedMetersPerSecond?.let { node.outputs["varFixSpeed"]?.bind(writes, Value.Num(it)) }
        fix.accuracyMeters?.let { node.outputs["varFixAccuracy"]?.bind(writes, Value.Num(it)) }
        fix.timestampMillis?.let { node.outputs["varFixTimestamp"]?.bind(writes, Value.Num(it.toDouble())) }
        fix.provider?.let { node.outputs["varFixProvider"]?.bind(writes, Value.Text(it)) }
        // varFixFlags is intentionally left unbound — the seam does not model Automate's fix bitmask.
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `location_provider_enabled` (Location provider enabled) — is a location provider on right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It parses `provider` (default GPS), reads
 * the enabled state through the seam, and routes YES when enabled, NO when disabled. A state the seam
 * cannot read is a named [Outcome.Fail], never a silent NO — an unreadable provider state must be
 * visible, not misreported as "off". An unrecognized `provider` string Fails by name, exactly as
 * `location_get` does.
 */
internal class LocationProviderEnabledBlock(
    private val locationProvider: () -> LocationReader?,
) : BlockImpl {
    override val specId = "location_provider_enabled"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = locationProvider() ?: return Outcome.Fail(LOCATION_ABSENT)
        val provider = args["provider"].asProviderOrDefault(LocationProvider.GPS)
            ?: return Outcome.Fail(
                "location_provider_enabled: unrecognized location provider (expected gps/network/passive/balanced).",
            )
        val enabled = reader.isProviderEnabled(provider)
            ?: return Outcome.Fail("location_provider_enabled: the provider state could not be read.")
        return Outcome.Proceed(if (enabled) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The five registered Location one-shot impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [sensorLookup], [powerLookup], [settingsLookup] and [appsLookup]: it always returns the map,
 * and the honest gate is the per-block gate-at-run (each fails with [LOCATION_ABSENT] when the provider
 * yields no seam), so a caller composes over its base registry exactly as the other categories do:
 *
 * ```
 * val location = locationLookup(locationReader)
 * fun lookup(id: String): BlockImpl? =
 *     location[id] ?: sensors[id] ?: power[id] ?: settings[id] ?: apps[id] ?: baseRegistry.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's remaining Location blocks are deliberately **not** here, so at run time the scheduler
 * finds no impl and gates them by the honest-by-omission mechanism the catalog's own `requires` set (or
 * the block's own shape) expresses. Unlike the SHELL *reads* `device_idle_mode_active`/`device_interactive`
 * in the Battery&Power slice — which are built-but-unregistered because an unprivileged read API happens
 * to exist behind the seam — every gated Location block is a *write*, a *UI*, a *network call* or an
 * *over-time await*, none of which the read-only [LocationReader] seam can host, so there is nothing to
 * build-but-not-register here; they are omitted entirely on four honest grounds:
 *  - **Privileged write, `SHELL`.** `location_provider_set_state` enables/disables a provider and carries
 *    `Requirement.Uid2000`. Turning a provider on/off is a privileged mutation with no normal-API and no
 *    read-seam expression, so — exactly as `power_save_mode_set_state` and the SHELL writes
 *    (`device_reboot`, `cpu_speed_set`, …) are gated in the Battery&Power slice — registering a no-shell
 *    impl would fake a write that would always be refused and would contradict the catalog's SHELL tag.
 *  - **Privileged write, mock provider.** `location_mock` injects a fake fix from a mock provider; it is
 *    a write needing the mock-location app grant, not a read this seam can honestly model.
 *  - **Interactive UI.** `location_pick` is a map picker and `location_show` launches the map app — both
 *    are user-facing UI, not device-state reads.
 *  - **Network service, and over-time await.** `weather` is a call to a network weather service, not a
 *    device read through `LocationManager`/`Geocoder`; and the `ON_ENTER`/`ON_EXIT` geofence form of
 *    `location_at` is a monitored await over time, which waits for the monitor subsystem this build does
 *    not have rather than being flattened into a dishonest one-shot (its `IMMEDIATELY` form *is*
 *    registered above).
 */
fun locationLookup(provider: () -> LocationReader?): Map<String, BlockImpl> = listOf(
    GeocodingReverseBlock(provider),
    GeocodingBlock(provider),
    LocationAtBlock(provider),
    LocationGetBlock(provider),
    LocationProviderEnabledBlock(provider),
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}
