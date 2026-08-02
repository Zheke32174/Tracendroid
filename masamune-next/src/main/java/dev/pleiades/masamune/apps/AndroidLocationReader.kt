package dev.pleiades.masamune.apps

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * The real, device-backed [LocationReader] — the Android glue that turns the plain-data contract into
 * reads of `LocationManager` (last-known fix, provider-enabled state) and a blocking `Geocoder` (both
 * directions of geocoding).
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit
 * tests' point of view: the blocks never see it, they see [LocationReader]. Keeping every framework call
 * on this side of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.LocationBlocks] stay
 * JVM-testable against a fake.
 *
 * ### Honest boundaries — a missing reading is `null`, never a fabricated fix
 *  - **Permission absent is `null`, not a guess.** `getLastKnownLocation` throws `SecurityException`
 *    when `ACCESS_FINE_LOCATION` is not granted; it is caught and the read returns `null` — a named Fail
 *    downstream, never a fabricated `0,0`. This is the block Failing "by name" on a missing grant rather
 *    than pretending to know where the device is.
 *  - **No fix is `null`.** A provider with no cached last-known location returns `null`; the freshest of
 *    the candidate providers is chosen, and if none has a fix the read is `null`.
 *  - **A stale fix is `null` when `maxFixAge` is set.** The block passes `location_get`'s `maxFixAge`
 *    straight through; a fix older than that (by the fix's own timestamp against wall-clock now) is
 *    dropped to `null` rather than returned as if fresh.
 *  - **Optional fields are guarded, not assumed.** Altitude/bearing/speed/accuracy are read only when
 *    the fix's `has*` flag is set, so a coarse fix with no bearing yields `null` (an unbound output),
 *    never a fabricated `0` a flow would read as "heading true north".
 *  - **A geocoder that cannot answer is `null`; a clean empty answer is a no-match.** `Geocoder.isPresent`
 *    false, or an `IOException` from an unreachable backend, returns `null` (→ a named Fail); a backend
 *    that answers with an empty list returns [ReverseGeocode.NoMatch] / [ForwardGeocode.NoMatch] (→ the
 *    decision's NO). The two are kept distinct so "no address here" is never confused with "the geocoder
 *    is down".
 */
class AndroidLocationReader(private val context: Context) : LocationReader {

    private val locationManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override suspend fun currentLocation(
        provider: LocationProvider,
        maxFixAgeMillis: Long?,
    ): LocationFix? {
        val manager = locationManager ?: return null
        val freshest = provider.candidateProviderNames()
            .mapNotNull { name -> lastKnownOrNull(manager, name) }
            .maxByOrNull { it.time }
            ?: return null // no candidate provider had a fix (or permission was absent)
        if (maxFixAgeMillis != null && System.currentTimeMillis() - freshest.time > maxFixAgeMillis) {
            return null // the freshest fix is older than the caller's age limit — honest "no fresh fix"
        }
        return freshest.toFix()
    }

    override suspend fun isProviderEnabled(provider: LocationProvider): Boolean? {
        val manager = locationManager ?: return null
        return provider.candidateProviderNames().firstNotNullOfOrNull { name ->
            try {
                if (manager.isProviderEnabled(name)) true else false
            } catch (_: IllegalArgumentException) {
                null // this device does not know this provider name — try the next candidate
            } catch (_: SecurityException) {
                null
            }
        }
    }

    override suspend fun reverseGeocode(
        latitude: Double,
        longitude: Double,
        languageTag: String?,
    ): ReverseGeocode? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, localeFor(languageTag))
        val addresses = try {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(latitude, longitude, 1)
        } catch (_: IOException) {
            return@withContext null // backend unreachable — a named Fail, not a no-match
        } catch (_: IllegalArgumentException) {
            return@withContext null // a latitude/longitude out of range
        }
        val address = addresses?.firstOrNull() ?: return@withContext ReverseGeocode.NoMatch
        ReverseGeocode.Matched(address.toGeocoded())
    }

    override suspend fun forwardGeocode(
        locationName: String,
        languageTag: String?,
    ): ForwardGeocode? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, localeFor(languageTag))
        val addresses = try {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(locationName, 1)
        } catch (_: IOException) {
            return@withContext null // backend unreachable — a named Fail, not a no-match
        }
        val hit = addresses?.firstOrNull()?.takeIf { it.hasLatitude() && it.hasLongitude() }
            ?: return@withContext ForwardGeocode.NoMatch
        ForwardGeocode.Matched(hit.latitude, hit.longitude)
    }

    /** The last-known fix for [name], or `null` when there is none or permission is absent. */
    private fun lastKnownOrNull(manager: LocationManager, name: String): Location? = try {
        manager.getLastKnownLocation(name)
    } catch (_: SecurityException) {
        null // ACCESS_FINE_LOCATION not granted — honest null, the block Fails by name
    } catch (_: IllegalArgumentException) {
        null // this device does not provide this provider
    }

    /** Reduce an `android.location.Location` to the plain-data [LocationFix], guarding optional fields. */
    private fun Location.toFix(): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
        speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        timestampMillis = time.takeIf { it > 0 },
        provider = provider,
    )

    /** Reduce an `android.location.Address` to the plain-data [GeocodedAddress]. */
    private fun Address.toGeocoded(): GeocodedAddress = GeocodedAddress(
        locationName = featureName ?: getAddressLine(0),
        addressLines = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) },
        featureName = featureName,
        thoroughfare = thoroughfare,
        subThoroughfare = subThoroughfare,
        locality = locality,
        subLocality = subLocality,
        adminArea = adminArea,
        subAdminArea = subAdminArea,
        postalCode = postalCode,
        countryName = countryName,
        countryCode = countryCode,
    )

    /** The Android provider names to try for a [LocationProvider], freshest-wins across the list. */
    private fun LocationProvider.candidateProviderNames(): List<String> = when (this) {
        LocationProvider.GPS -> listOf(LocationManager.GPS_PROVIDER)
        LocationProvider.NETWORK -> listOf(LocationManager.NETWORK_PROVIDER)
        LocationProvider.PASSIVE -> listOf(LocationManager.PASSIVE_PROVIDER)
        // "Balanced" maps to the fused provider where it exists (API 31+), falling back to the
        // network provider, then GPS — the honest coarse-to-fine order for a low-power fix.
        LocationProvider.BALANCED ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            } else {
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            }
    }

    private fun localeFor(languageTag: String?): Locale =
        languageTag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
}
