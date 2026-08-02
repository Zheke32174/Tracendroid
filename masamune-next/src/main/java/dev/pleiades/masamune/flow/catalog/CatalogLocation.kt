package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.ProceedMode

/**
 * Where the device is, and what that place is called.
 *
 * `Location at` is the canonical demonstration of organ 2 and worth reading first: with
 * [ProceedMode.IMMEDIATELY] it asks whether you are somewhere, and with
 * [ProceedMode.ON_ENTER] it is a geofence. One block, one spec, two products.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val LOCATION_BLOCKS: List<BlockSpec> = category(BlockCategory.LOCATION) {
    decision(
        "geocoding_reverse", "Geocoding reverse",
        "Finds the address or location name for a geographic coordinate.",
        args = listOf(
            num("latitude", "Latitude"),
            num("longitude", "Longitude"),
            any("language", "Language", "the device language"),
        ),
        outputs = listOf(
            out("varLocationName", "Location name"),
            out("varAddressLines", "Address lines"),
            out("varFeatureName", "Feature name"),
            out("varThoroughfare", "Thoroughfare name"),
            out("varSubThoroughfare", "Sub-thoroughfare name"),
            out("varLocality", "Locality name"),
            out("varSubLocality", "Sub-locality name"),
            out("varAdminArea", "Administrative area name"),
            out("varSubAdminArea", "Sub-administrative area name"),
            out("varPostalCode", "Postal code"),
            out("varCountryName", "Country name"),
            out("varCountryCode", "Country code"),
        ),
    )
    decision(
        "geocoding", "Geocoding",
        "Finds the geographic coordinate of an address or location name.",
        args = listOf(
            any("locationName", "Location name"),
            any("language", "Language", "the device language"),
        ),
        outputs = listOf(
            out("varDecodedLatitude", "Decoded latitude"),
            out("varDecodedLongitude", "Decoded longitude"),
        ),
    )
    decision(
        "location_at", "Location at",
        "Checks if the device is at a location, geographic coordinate.",
        proceed = WATCH,
        args = listOf(
            num("latitude", "Latitude"),
            num("longitude", "Longitude"),
            num("radius", "Radius", "250 meters"),
            num("responsiveness", "Responsiveness", "30 seconds"),
        ),
        requires = setOf(ACCESS_FINE_LOCATION),
    )
    action(
        "location_get", "Location get",
        "Gets the current device location, geographic coordinate.",
        proceed = WATCH_VALUE,
        args = listOf(
            any("provider", "Location provider", "Balanced"),
            any(
                "maxFixAge", "Maximum fix age",
                "no age limit, only used with proceed Maybe immediately",
            ),
            num(
                "minDistance", "Minimum distance",
                "100 meters, only used with proceed When changed",
            ),
        ),
        outputs = listOf(
            out("varFixLatitude", "Location fix latitude"),
            out("varFixLongitude", "Location fix longitude"),
            out("varFixAltitude", "Location fix altitude"),
            out("varFixBearing", "Location fix bearing"),
            out("varFixSpeed", "Location fix speed"),
            out("varFixAccuracy", "Location fix accuracy"),
            out("varFixFlags", "Location fix flags"),
            out("varFixTimestamp", "Location fix timestamp"),
            out("varFixProvider", "Location fix provider"),
        ),
        requires = setOf(ACCESS_FINE_LOCATION),
    )
    action(
        "location_mock", "Location mock",
        "Mocks (fakes) a location fix update originating from provider.",
        args = listOf(
            any("provider", "Location provider", "GPS"),
            num("latitude", "Latitude"),
            num("longitude", "Longitude"),
        ),
        requires = setOf(ACCESS_FINE_LOCATION),
    )
    decision(
        "location_pick", "Location pick",
        "Lets the user choose a location on a map.",
        args = listOf(
            num("initialLatitude", "Initial latitude"),
            num("initialLongitude", "Initial longitude"),
            flag("radiusSelection", "Radius selection"),
            num("defaultRadius", "Default radius", "250 meters"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varPickedLatitude", "Picked latitude"),
            out("varPickedLongitude", "Picked longitude"),
            out("varPickedRadius", "Picked radius"),
        ),
    )
    decision(
        "location_provider_enabled", "Location provider enabled",
        "Checks if a specific location provider is enabled.",
        proceed = WATCH,
        args = listOf(
            any("provider", "Location provider", "GPS"),
        ),
    )
    action(
        "location_provider_set_state", "Location provider set state",
        "Enables or disables a location provider.",
        args = listOf(
            flag("state", "State"),
            any("provider", "Location provider", "GPS"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "location_show", "Location show",
        "Shows a location in the default map app.",
        args = listOf(
            num("latitude", "Latitude"),
            num("longitude", "Longitude"),
            text("locationName", "Location name"),
            num("zoom", "Zoom level"),
            text("label", "Marker label"),
        ),
    )
    decision(
        "weather", "Weather",
        "Gets the current weather, or a forecast, for a geographic coordinate.",
        args = listOf(
            num("latitude", "Latitude"),
            num("longitude", "Longitude"),
            num("advance", "Forecast advance", "current weather"),
            num("period", "Forecast period"),
        ),
        outputs = listOf(
            out("varTemperature", "Temperature"),
            out("varHumidity", "Humidity"),
            out("varPressure", "Atmospheric pressure"),
            out("varCloudiness", "Cloudiness"),
            out("varWindSpeed", "Wind speed"),
            out("varWindDirection", "Wind direction"),
            out("varRain", "Rain volume"),
            out("varSnow", "Snow volume"),
            out("varForecastTime", "Forecast timestamp"),
        ),
    )
}
