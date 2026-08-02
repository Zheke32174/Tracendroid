package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.model.ProceedMode
import dev.pleiades.masamune.flow.model.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is data, so its tests are conformance tests against the donor, not behaviour
 * tests.
 *
 * That is the point of them. A hand-entered table of 418 rows drifts silently — a block gets
 * dropped in a merge, an id gets retyped, a decision loses its NO path — and none of those
 * shows up as a crash. They show up as a flow that routes wrong six months later. Every number
 * asserted below comes from `docs/donors/RE-automate.md`, which is the authority; if the donor
 * doc and this file disagree, this file is wrong.
 */
class BlockCatalogTest {

    /**
     * Automate's own per-category counts, verbatim from the donor doc's headings.
     *
     * Written out rather than derived from the catalog, which would make the assertion
     * tautological. These sixteen numbers are the transcription check.
     */
    private val donorCounts = mapOf(
        BlockCategory.APPS to 43,
        BlockCategory.BATTERY_AND_POWER to 17,
        BlockCategory.CAMERA_AND_SOUND to 52,
        BlockCategory.CONCURRENCY to 7,
        BlockCategory.CONNECTIVITY to 51,
        BlockCategory.CONTENT to 30,
        BlockCategory.DATE_AND_TIME to 7,
        BlockCategory.STORAGE to 36,
        BlockCategory.FLOW to 13,
        BlockCategory.GENERAL to 10,
        BlockCategory.INTERFACE to 66,
        BlockCategory.LOCATION to 10,
        BlockCategory.MESSAGING to 12,
        BlockCategory.SENSOR to 15,
        BlockCategory.SETTINGS to 24,
        BlockCategory.TELEPHONY to 25,
    )

    @Test
    fun `holds all 418 donor blocks`() {
        assertEquals(418, BlockCatalog.all.size)
        assertEquals(418, BlockCatalog.size)
    }

    @Test
    fun `every block id is unique`() {
        val ids = BlockCatalog.all.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("duplicate block ids", emptySet<String>(), duplicates)
        assertEquals(418, ids.toSet().size)
    }

    @Test
    fun `every category is non-empty`() {
        for (category in BlockCategory.entries) {
            assertTrue(
                "category ${category.label} has no blocks",
                BlockCatalog.of(category).isNotEmpty(),
            )
        }
        assertEquals(16, BlockCategory.entries.size)
    }

    @Test
    fun `per-category counts match the donor documentation`() {
        for ((category, expected) in donorCounts) {
            assertEquals(
                "block count for ${category.label}",
                expected,
                BlockCatalog.of(category).size,
            )
        }
        assertEquals(418, donorCounts.values.sum())
    }

    /**
     * The two-shape grammar, asserted from the outside.
     *
     * `Port.of` already derives this, so the value here is that it holds for every spec in the
     * catalog — a block mistyped as an `ACTION` is exactly the failure that produces a flow
     * with an unreachable branch and no error anywhere.
     */
    @Test
    fun `decisions expose YES and NO while actions expose only OK`() {
        for (spec in BlockCatalog.all) {
            when (spec.shape) {
                BlockShape.DECISION -> assertEquals(
                    "ports of decision ${spec.id}",
                    listOf(Port.YES, Port.NO),
                    spec.ports,
                )

                BlockShape.ACTION -> assertEquals(
                    "ports of action ${spec.id}",
                    listOf(Port.OK),
                    spec.ports,
                )
            }
        }
    }

    @Test
    fun `both shapes are actually used`() {
        val decisions = BlockCatalog.ofShape(BlockShape.DECISION)
        val actions = BlockCatalog.ofShape(BlockShape.ACTION)
        assertTrue(decisions.isNotEmpty())
        assertTrue(actions.isNotEmpty())
        assertEquals(418, decisions.size + actions.size)
    }

    @Test
    fun `every spec is indexed by its own id`() {
        for (spec in BlockCatalog.all) {
            assertEquals(spec, BlockCatalog[spec.id])
            assertEquals(spec, BlockCatalog.require(spec.id))
        }
        assertNull(BlockCatalog["no_such_block"])
    }

    @Test
    fun `flattened order matches category order`() {
        val fromCategories = BlockCategory.entries.flatMap { BlockCatalog.of(it) }
        assertEquals(BlockCatalog.all, fromCategories)
    }

    /**
     * No placeholder rows.
     *
     * A catalog is the one place where a stub is invisible: it renders, it drags onto the
     * canvas, and it does nothing. Blank text and TODO markers are the shapes that failure
     * takes, so they are asserted against directly.
     */
    @Test
    fun `no spec carries blank or placeholder text`() {
        val markers = listOf("TODO", "FIXME", "XXX", "placeholder", "tbd")
        for (spec in BlockCatalog.all) {
            assertTrue("blank id", spec.id.isNotBlank())
            assertTrue("blank name for ${spec.id}", spec.name.isNotBlank())
            assertTrue("blank summary for ${spec.id}", spec.summary.isNotBlank())
            for (marker in markers) {
                assertTrue(
                    "placeholder '$marker' in ${spec.id}",
                    !spec.summary.contains(marker, ignoreCase = true),
                )
            }
            for (arg in spec.args) {
                assertTrue("blank arg key in ${spec.id}", arg.key.isNotBlank())
                assertTrue("blank arg label in ${spec.id}", arg.label.isNotBlank())
            }
            for (output in spec.outputs) {
                assertTrue("blank output key in ${spec.id}", output.key.isNotBlank())
                assertTrue("blank output label in ${spec.id}", output.label.isNotBlank())
            }
        }
    }

    /**
     * Argument and output keys are per-block field identifiers, so a collision inside one
     * block means one of the two fields can never be addressed.
     */
    @Test
    fun `argument and output keys are unique within a block`() {
        for (spec in BlockCatalog.all) {
            assertEquals(
                "duplicate arg keys in ${spec.id}",
                spec.args.size,
                spec.args.map { it.key }.toSet().size,
            )
            assertEquals(
                "duplicate output keys in ${spec.id}",
                spec.outputs.size,
                spec.outputs.map { it.key }.toSet().size,
            )
        }
    }

    @Test
    fun `every option default names one of its own choices`() {
        for (spec in BlockCatalog.all) {
            for (option in spec.options) {
                assertTrue("option ${option.key} of ${spec.id} has no choices", option.choices.isNotEmpty())
                assertTrue(
                    "default '${option.defaultChoice}' of ${spec.id}.${option.key} is not a choice",
                    option.choices.any { it.value == option.defaultChoice },
                )
            }
        }
    }

    @Test
    fun `proceed modes are distinct within a spec`() {
        for (spec in BlockCatalog.all) {
            assertEquals(
                "duplicate proceed modes in ${spec.id}",
                spec.proceedModes.size,
                spec.proceedModes.toSet().size,
            )
        }
    }

    // ------------------------------------------------------------------ gating

    /**
     * The interaction family gates on the accessibility service.
     *
     * Named block by block rather than pattern-matched: these are the blocks the port brief
     * calls out, and the value of the test is that removing the gate from any one of them
     * fails here instead of shipping a block that silently no-ops.
     */
    @Test
    fun `interaction family requires the accessibility service`() {
        val expected = listOf(
            "interact", "interact_touch", "inspect_layout", "inspect_text_edit",
            "key_send", "key_send_characters", "key_pressed",
        )
        for (id in expected) {
            val spec = BlockCatalog.require(id)
            assertTrue(
                "${spec.name} must gate on the accessibility service",
                Requirement.Accessibility in spec.requires,
            )
        }
    }

    @Test
    fun `notification family requires notification access`() {
        val expected = listOf(
            "notification_posted", "notification_interact", "notification_cancel",
            "notification_snooze",
        )
        for (id in expected) {
            val spec = BlockCatalog.require(id)
            assertTrue(
                "${spec.name} must gate on notification access",
                Requirement.NotificationListener in spec.requires,
            )
        }
    }

    @Test
    fun `shell tier requires the privileged uid`() {
        val expected = listOf("shell_command_privileged", "adb_shell_command", "app_op_mode_set")
        for (id in expected) {
            val spec = BlockCatalog.require(id)
            assertTrue(
                "${spec.name} must gate on the privileged shell",
                Requirement.Uid2000 in spec.requires,
            )
        }
    }

    /**
     * `Shell command superuser` gates on uid 2000 like the rest of the shell tier, but root is
     * strictly more than uid 2000 grants — so the summary has to say so, or the palette would
     * enable the block on a device that cannot run it.
     */
    @Test
    fun `superuser shell names root as a stricter tier than uid 2000`() {
        val spec = BlockCatalog.require("shell_command_superuser")
        assertTrue(Requirement.Uid2000 in spec.requires)
        assertTrue(
            "summary must name root as a separate tier: ${spec.summary}",
            spec.summary.contains("root", ignoreCase = true),
        )
    }

    /** Plain `Shell command` runs in the app sandbox and must not claim a privilege gate. */
    @Test
    fun `unprivileged shell command is not gated`() {
        assertTrue(BlockCatalog.require("shell_command").requires.isEmpty())
    }

    @Test
    fun `device lock family requires device admin`() {
        for (id in listOf("device_lock", "screen_lock_set_state")) {
            assertTrue(
                "$id must gate on device admin",
                Requirement.DeviceAdmin in BlockCatalog.require(id).requires,
            )
        }
    }

    @Test
    fun `permission gates name a fully qualified android permission`() {
        val permissions = BlockCatalog.allRequirements.filterIsInstance<Requirement.Permission>()
        assertTrue("catalog declares no runtime permissions", permissions.isNotEmpty())
        for (permission in permissions) {
            assertTrue(
                "not a fully qualified permission: ${permission.androidPermission}",
                permission.androidPermission.startsWith("android.permission."),
            )
            assertTrue(permission.label.startsWith("Permission: "))
        }
    }

    @Test
    fun `location blocks require location permission`() {
        for (id in listOf("location_at", "location_get", "location_mock")) {
            val perms = BlockCatalog.require(id).requires
                .filterIsInstance<Requirement.Permission>()
                .map { it.androidPermission }
            assertTrue("$id must require a location permission", perms.any { "LOCATION" in it })
        }
    }

    // ------------------------------------------------------- catalog behaviour

    @Test
    fun `placeability follows the requirement set`() {
        val interact = BlockCatalog.require("interact")
        val start = BlockCatalog.require("activity_start")

        assertTrue(BlockCatalog.isPlaceable(start, emptySet()))
        assertTrue(!BlockCatalog.isPlaceable(interact, emptySet()))
        assertEquals(
            setOf<Requirement>(Requirement.Accessibility),
            BlockCatalog.missingRequirements(interact, emptySet()),
        )
        assertTrue(BlockCatalog.isPlaceable(interact, setOf(Requirement.Accessibility)))
        assertTrue(BlockCatalog.missingRequirements(start, emptySet()).isEmpty())
    }

    @Test
    fun `nothing is blocked when every requirement is satisfied`() {
        assertTrue(BlockCatalog.blockedBy(BlockCatalog.allRequirements).isEmpty())
        assertTrue(BlockCatalog.blockedBy(emptySet()).isNotEmpty())
    }

    @Test
    fun `search ranks name matches ahead of summary matches`() {
        val results = BlockCatalog.search("Wi-Fi enabled")
        assertEquals("Wi-Fi enabled", results.first().name)

        val byId = BlockCatalog.search("wifi_signal_level")
        assertEquals("wifi_signal_level", byId.first().id)

        assertEquals(418, BlockCatalog.search("  ").size)
        assertTrue(BlockCatalog.search("zzzzz-not-a-block").isEmpty())
    }

    @Test
    fun `spot-checked specs match the donor documentation`() {
        // A decision whose Proceed option is the whole of organ 2.
        val locationAt = BlockCatalog.require("location_at")
        assertEquals(BlockShape.DECISION, locationAt.shape)
        assertEquals(BlockCategory.LOCATION, locationAt.category)
        assertEquals(
            listOf(
                ProceedMode.IMMEDIATELY,
                ProceedMode.ON_ENTER,
                ProceedMode.ON_EXIT,
                ProceedMode.ON_CHANGE,
            ),
            locationAt.proceedModes,
        )

        // An action with a documented default on one of its arguments.
        val appInstalled = BlockCatalog.require("app_installed")
        assertEquals(BlockShape.DECISION, appInstalled.shape)
        assertNotNull(appInstalled.args.firstOrNull { it.key == "packageName" })
        assertTrue(appInstalled.outputs.any { it.key == "varVersionName" })

        // Automate's special blocks, mapped onto the two shapes.
        assertEquals(BlockShape.DECISION, BlockCatalog.require("fork").shape)
        assertEquals(BlockShape.DECISION, BlockCatalog.require("for_each").shape)
        assertEquals(BlockShape.DECISION, BlockCatalog.require("subroutine").shape)
        assertEquals(BlockShape.DECISION, BlockCatalog.require("failure_catch").shape)
        assertEquals(BlockShape.ACTION, BlockCatalog.require("goto").shape)
        assertEquals(BlockShape.ACTION, BlockCatalog.require("label").shape)
        assertEquals(BlockShape.ACTION, BlockCatalog.require("flow_beginning").shape)
    }

    /**
     * Every argument is optional, exactly as the donor states.
     *
     * Automate's own documentation says input arguments "can be left unspecified, then a
     * sensible default will be used". Marking one required here would make the editor refuse a
     * flow the donor accepts, so the invariant is asserted rather than left to per-block care.
     */
    @Test
    fun `all arguments are optional`() {
        for (spec in BlockCatalog.all) {
            for (arg in spec.args) {
                assertTrue("${spec.id}.${arg.key} must be optional", arg.optional)
            }
        }
    }
}
