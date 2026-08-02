package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * The hardware sensors, each as a threshold decision rather than a raw reading.
 *
 * Automate's consistent shape here is worth preserving exactly: nearly every sensor block
 * takes a minimum and a maximum and answers whether the reading is inside the band, so the
 * same spec both samples the sensor now and waits for it to cross. A catalog that exposed raw
 * values instead would need a comparison block after every sensor.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val SENSOR_BLOCKS: List<BlockSpec> = category(BlockCategory.SENSOR) {
    decision(
        "ambient_light", "Ambient light",
        "Checks the ambient light using the built-in sensor.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum level"),
            num("maxLevel", "Maximum level"),
        ),
        outputs = listOf(
            out("varLevel", "Current level"),
        ),
    )
    decision(
        "ambient_temperature", "Ambient temperature",
        "Checks the ambient temperature using the built-in sensor.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum temperature"),
            num("maxLevel", "Maximum temperature"),
        ),
        outputs = listOf(
            out("varLevel", "Current temperature"),
        ),
    )
    decision(
        "atmospheric_pressure", "Atmospheric pressure",
        "Checks the atmospheric pressure using the built-in sensor.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum pressure"),
            num("maxLevel", "Maximum pressure"),
        ),
        outputs = listOf(
            out("varLevel", "Current pressure"),
        ),
    )
    decision(
        "device_acceleration", "Device acceleration",
        "Checks the acceleration of the device using the built-in sensor.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum acceleration"),
            num("maxLevel", "Maximum acceleration"),
        ),
        outputs = listOf(
            out("varLevel", "Current acceleration"),
        ),
    )
    decision(
        "device_orientation", "Device orientation",
        "Checks the device orientation using the built-in sensors.",
        proceed = WATCH,
        args = listOf(
            num("azimuth", "Azimuth"),
            num("pitch", "Pitch"),
            num("roll", "Roll"),
            num("tolerance", "Tolerance", "30"),
        ),
        outputs = listOf(
            out("varCurrentAzimuth", "Azimuth"),
            out("varCurrentPitch", "Pitch"),
            out("varCurrentRoll", "Roll"),
        ),
    )
    decision(
        "heart_rate", "Heart rate",
        "Checks heart rate using available sensor.",
        proceed = WATCH,
        args = listOf(
            any("minLevel", "Minimum rate"),
            any("maxLevel", "Maximum rate"),
        ),
        outputs = listOf(
            out("varLevel", "Current rate"),
        ),
        requires = setOf(BODY_SENSORS),
    )
    decision(
        "hinge_angle", "Device hinge angle",
        "Checks hinge angle of a foldable device.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum angle"),
            num("maxLevel", "Maximum angle"),
        ),
        outputs = listOf(
            out("varLevel", "Current angle"),
        ),
    )
    decision(
        "magnetic_field_strength", "Magnetic field strength",
        "Checks the surrounding magnetic field strength using the built-in sensor.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum field strength"),
            num("maxLevel", "Maximum field strength"),
        ),
        outputs = listOf(
            out("varLevel", "Current field strength"),
        ),
    )
    action(
        "motion_gesture", "Motion gesture",
        "Waits for a device motion gesture, like a shake, twist, etc.",
    )
    action(
        "pedometer", "Pedometer",
        "Counts each step a person takes.",
        proceed = AWAIT,
        args = listOf(
            num("minSteps", "Minimum steps", "1"),
            num("stillDuration", "Minimum standstill duration", "5 seconds"),
        ),
        outputs = listOf(
            out("varStepCount", "Steps taken"),
            out("varLastStepTime", "Last step"),
        ),
        requires = setOf(ACTIVITY_RECOGNITION),
    )
    action(
        "physical_activity", "Physical activity",
        "Awaits a physical activity such as walking or running.",
        args = listOf(
            any("activities", "Activities"),
            num("minConfidence", "Minimum confidence", "0"),
            num("interval", "Detection interval", "30 seconds"),
        ),
        outputs = listOf(
            out("varCurrentActivity", "Current activity"),
            out("varConfidence", "Confidence"),
        ),
        requires = setOf(ACTIVITY_RECOGNITION),
    )
    decision(
        "proximity", "Proximity",
        "Checks the proximity, distance to the device using the built-in sensor.",
        proceed = WATCH,
        args = listOf(
            any("minLevel", "Minimum distance"),
            any("maxLevel", "Maximum distance"),
        ),
        outputs = listOf(
            out("varLevel", "Current distance"),
        ),
    )
    decision(
        "relative_humidity", "Relative humidity",
        "Checks the relative ambient air humidity using the built-in sensor.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum level"),
            num("maxLevel", "Maximum level"),
        ),
        outputs = listOf(
            out("varLevel", "Current level"),
        ),
    )
    action(
        "significant_device_motion", "Significant device motion",
        "Awaits a significant device motion occurring after being stationary/asleep.",
    )
    decision(
        "user_asleep", "User asleep",
        "Awaits the user falling asleep or waking up.",
        args = listOf(
            num("minConfidence", "Minimum confidence"),
            num("maxConfidence", "Maximum confidence"),
        ),
        outputs = listOf(
            out("varConfidence", "Confidence"),
            out("varAmbientLight", "Ambient light"),
            out("varDeviceMotion", "Device motion"),
        ),
        requires = setOf(ACTIVITY_RECOGNITION),
    )
}
