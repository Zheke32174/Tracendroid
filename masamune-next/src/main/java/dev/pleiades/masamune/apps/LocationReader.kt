package dev.pleiades.masamune.apps

/**
 * The seam between the Location-category block impls and the real device location subsystem.
 *
 * Every way an unprivileged, one-shot Location block can *read* the device's place — its current
 * location fix, whether a given provider is enabled, and the two directions of geocoding
 * (coordinate → address, address → coordinate) — is one method here, and — exactly like
 * [AppInspector] does for the Apps blocks, [SystemSettings] for the Settings blocks, [PowerState] for
 * the Battery&Power blocks and [SensorReader] for the Sensor blocks — there is deliberately nothing
 * `android.*` on this interface. That single constraint is what buys the whole slice its
 * JVM-testability: [dev.pleiades.masamune.flow.runtime.impl.LocationBlocks] depend on this plain-data
 * contract, never on `LocationManager`, an `android.location.Location`, or a `Geocoder`, so every block
 * and all its branch logic can be exercised against a fake on an ordinary unit-test JVM. A device is
 * needed to *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidLocationReader]) is not wired
 * in, there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.LOCATION_ABSENT]) rather than reporting a place it never
 * actually read.
 *
 * ### Honest failure shapes: not-present vs. no-match
 * The reads model two genuinely different "no answer" cases, and keeping them distinct is the whole
 * point of the honest-gating rule:
 *  - **`null` means "could not be read".** No location fix is available, location permission is not
 *    granted (the real impl catches the `SecurityException` and returns `null`), the provider state
 *    cannot be determined, or the geocoder backend cannot be reached. The block routes `null` to a
 *    visible [dev.pleiades.masamune.flow.runtime.Outcome.Fail] **by name** — it never fabricates a `0,0`
 *    coordinate a downstream block would trust as a real fix.
 *  - **An explicit no-match ([ReverseGeocode.NoMatch] / [ForwardGeocode.NoMatch]) means "read fine,
 *    but there is no such place".** A geocoder that answers "this coordinate has no address" is a real,
 *    successful read with a NO answer, distinct from a geocoder that could not be reached at all. The
 *    block routes a no-match to its NO branch, and a `null` to a named Fail — the two must never be
 *    conflated, so they carry different Kotlin shapes at the seam.
 *
 * This slice is entirely read-only: everything it can touch is unprivileged location *state*, so there
 * is no write result-type here. The catalog's one location *write* — `location_provider_set_state`,
 * which enables/disables a provider and carries `Requirement.Uid2000` (SHELL) — has no method here and
 * is gated by omission (see [dev.pleiades.masamune.flow.runtime.impl.locationLookup]'s KDoc), exactly
 * as the privileged power *writes* are gated in the Battery&Power slice.
 *
 * Every method is `suspend` because a location read is inherently asynchronous — the real impl touches
 * `LocationManager` / a blocking `Geocoder` off the caller's thread without the contract changing
 * shape; the fake simply returns.
 */
interface LocationReader {

    /**
     * A one-shot read of the device's most recent location fix from [provider], or `null` when no fix
     * is available, location permission is absent, or (when [maxFixAgeMillis] is set) the freshest fix
     * is older than that many milliseconds.
     *
     * The seam owns freshness so the block stays a pure branch: `location_get`'s `maxFixAge` argument —
     * which the catalog documents as "only used with proceed Maybe immediately", i.e. exactly this
     * one-shot form — is passed straight through, and the real impl drops a stale fix to `null` rather
     * than the block having to reach for a clock. `null` is the honest "cannot read": a block routes it
     * to a named Fail, never to a fabricated origin coordinate.
     */
    suspend fun currentLocation(provider: LocationProvider, maxFixAgeMillis: Long?): LocationFix?

    /**
     * Whether [provider] is currently enabled (location services on for it), or `null` when the state
     * cannot be determined (no `LocationManager`, or the provider name is not known to this device).
     *
     * A real `Boolean?`: `false` ("the provider is off") is a successful read the `location_provider_enabled`
     * decision routes to NO, distinct from `null` ("could not read the provider state") which it routes
     * to a named Fail. An unreadable provider state must be visible, never misreported as "disabled".
     */
    suspend fun isProviderEnabled(provider: LocationProvider): Boolean?

    /**
     * Reverse-geocode a coordinate to a postal address. Returns [ReverseGeocode.Matched] with the
     * address, [ReverseGeocode.NoMatch] when the geocoder answers but has no address for the
     * coordinate, or `null` when the geocoder backend cannot be reached at all.
     *
     * The three-valued return is deliberate: `geocoding_reverse` is a decision, so "found" is YES,
     * "no address here" is NO, and "the geocoder is unavailable" is a named Fail — three outcomes that
     * a plain nullable address could not tell apart.
     */
    suspend fun reverseGeocode(
        latitude: Double,
        longitude: Double,
        languageTag: String?,
    ): ReverseGeocode?

    /**
     * Forward-geocode a place name or address to a coordinate. Returns [ForwardGeocode.Matched] with
     * the coordinate, [ForwardGeocode.NoMatch] when the geocoder answers but finds no coordinate for
     * the name, or `null` when the geocoder backend cannot be reached at all. Three-valued for the same
     * reason as [reverseGeocode].
     */
    suspend fun forwardGeocode(locationName: String, languageTag: String?): ForwardGeocode?
}

/**
 * The location providers a one-shot Location block can name, as plain data — a real enum rather than a
 * leaked `LocationManager.*_PROVIDER` string. The mapping from a provider to its Android name (and the
 * API-level fallback for [BALANCED], which is `FUSED_PROVIDER` only from API 31) lives entirely in
 * [AndroidLocationReader], so nothing `android.*` crosses the seam.
 *
 *  - [GPS] — the satellite provider (`gps`); high accuracy, higher power.
 *  - [NETWORK] — cell/Wi-Fi derived (`network`); coarser, lower power.
 *  - [PASSIVE] — receives fixes other apps request, requesting none itself (`passive`).
 *  - [BALANCED] — Automate's "Balanced" default, mapped to the fused provider where available.
 */
enum class LocationProvider { GPS, NETWORK, PASSIVE, BALANCED }

/**
 * One location fix, reduced to the values the catalog's `location_get` outputs expect, as plain data.
 *
 * [latitude] and [longitude] are always present in a real fix; the rest are nullable because not every
 * fix carries them — a fix from a coarse provider may have no bearing or speed, and altitude is often
 * absent. A missing optional is `null`, and the block leaves the corresponding output *unbound* rather
 * than binding a fabricated `0` a downstream block would read as "moving due north at 0 m/s". Automate's
 * `varFixFlags` bitmask is not modelled here (this minimal seam does not expose it), so that output is
 * honestly left unbound rather than guessed.
 *
 *  - [altitudeMeters] — metres above the WGS 84 ellipsoid.
 *  - [bearingDegrees] — degrees of travel, `0..360`, clockwise from true north.
 *  - [speedMetersPerSecond] — ground speed in m/s.
 *  - [accuracyMeters] — the fix's horizontal accuracy radius in metres (68% confidence).
 *  - [timestampMillis] — the fix's UTC time, milliseconds since the epoch.
 *  - [provider] — the name of the provider that produced the fix.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    val timestampMillis: Long? = null,
    val provider: String? = null,
)

/**
 * A reverse-geocoded address, one nullable field per `geocoding_reverse` output, plus [addressLines]
 * (Automate returns the formatted address as a list of lines). Every field is nullable because a real
 * `android.location.Address` fills only the fields the backend knows; the block binds each present
 * field and leaves the absent ones unbound — never a fabricated empty string a flow would treat as a
 * real, blank locality.
 */
data class GeocodedAddress(
    val locationName: String? = null,
    val addressLines: List<String> = emptyList(),
    val featureName: String? = null,
    val thoroughfare: String? = null,
    val subThoroughfare: String? = null,
    val locality: String? = null,
    val subLocality: String? = null,
    val adminArea: String? = null,
    val subAdminArea: String? = null,
    val postalCode: String? = null,
    val countryName: String? = null,
    val countryCode: String? = null,
)

/**
 * The outcome of a reverse-geocode that the geocoder *could* answer. Distinct from the seam method's
 * `null` (the geocoder could not be reached), so a decision tells "no address here" (NO) apart from
 * "the geocoder is unavailable" (a named Fail).
 */
sealed interface ReverseGeocode {
    /** The geocoder found an address for the coordinate — the decision's YES branch. */
    data class Matched(val address: GeocodedAddress) : ReverseGeocode

    /** The geocoder answered but has no address for the coordinate — the decision's NO branch. */
    data object NoMatch : ReverseGeocode
}

/**
 * The outcome of a forward-geocode that the geocoder *could* answer, mirroring [ReverseGeocode] so the
 * `geocoding` decision routes YES on a match, NO on a clean no-match, and a named Fail on `null`.
 */
sealed interface ForwardGeocode {
    /** The geocoder resolved the name to a coordinate — the decision's YES branch. */
    data class Matched(val latitude: Double, val longitude: Double) : ForwardGeocode

    /** The geocoder answered but resolved the name to no coordinate — the decision's NO branch. */
    data object NoMatch : ForwardGeocode
}
