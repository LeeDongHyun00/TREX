package com.example.trex_kotlin.store

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level seals on the persistence layer.
 *
 * The app's claim is that nothing leaves the device and that the heuristic form-check track stores
 * nothing (§5-2 of `docs/pose-heuristic-form-check.v1.md`). Adding a durable store is exactly the
 * change that could quietly retire either claim, so the boundary is asserted here rather than left
 * to review: no pose output in the persisted vocabulary, no transport, no shared storage, and no
 * path by which a stored byte becomes posture authority.
 */
class TrexPersistenceGovernanceTest {

    private fun mainSources(): File = listOf("src/main/java", "app/src/main/java")
        .map(::File)
        .firstOrNull(File::isDirectory)
        ?: error("Main sources not found from ${File("").absolutePath}")

    private fun storeFiles(): List<File> {
        val store = mainSources().resolve("com/example/trex_kotlin/store")
        assertTrue("Store package is missing", store.isDirectory)
        val files = store.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Store package is empty", files.isNotEmpty())
        return files
    }

    @Test
    fun thePersistenceLayerStoresNothingPoseDerived() {
        // §5-2 stays intact under this change: the store's vocabulary cannot even name a detector
        // output. `reps` in the persisted model is the planned string the user typed.
        val forbidden = listOf(
            "landmark",
            "Landmark",
            "angle",
            "Angle",
            "repCount",
            "PostureCorrection",
            "formcheck",
            // Qualified, because "androidx.compose.ui" contains the bare substring "pose.".
            "trex_kotlin.pose",
        )

        for (file in storeFiles()) {
            val text = file.readText()
            for (needle in forbidden) {
                assertFalse("${file.name} references \"$needle\"", text.contains(needle))
            }
        }
    }

    @Test
    fun thePersistenceLayerHasNoTransportAndNoSharedStorage() {
        // The store writes to the app's private filesDir and nowhere else. Anything below would
        // either put user data somewhere another app can read it, or off the device entirely.
        val forbidden = listOf(
            "http",
            "Socket",
            "URL",
            "getExternalFilesDir",
            "getExternalStorage",
            "Environment.",
            "MediaStore",
            "ContentResolver",
            "SharedPreferences",
        )

        for (file in storeFiles()) {
            val text = file.readText()
            for (needle in forbidden) {
                assertFalse("${file.name} uses $needle", text.contains(needle))
            }
        }
    }

    @Test
    fun onlyTheFileStoreTouchesTheFilesystem() {
        // Two files may name java.io at all: the store itself, and the one that hands it the path.
        // Keeping the surface this small is what makes the transport rule above cheap to audit.
        val naming = storeFiles()
            .filter { it.readText().contains("java.io") }
            .map(File::getName)
            .sorted()

        assertEquals(listOf("FileTrexStore.kt", "RememberTrexStore.kt"), naming)

        // And only one of them may actually move bytes. RememberTrexStore builds a File and stops.
        val reading = storeFiles()
            .filter { file ->
                val text = file.readText()
                listOf("readText", "writeText", "readBytes", "writeBytes", "renameTo")
                    .any(text::contains)
            }
            .map(File::getName)

        assertEquals(listOf("FileTrexStore.kt"), reading)
    }

    @Test
    fun nothingRestoredFromDiskCanOpenAPostureSession() {
        // PlacementCoachGovernanceTest already forbids "posture = true" across all of src/main.
        // This states the same rule from the store's side, so a future edit here fails with a
        // message about persistence rather than about evaluation sessions.
        for (file in storeFiles()) {
            val text = file.readText()
            assertFalse("${file.name} sets posture", text.contains("posture = true"))
            assertFalse("${file.name} grants posture", text.contains("withPostureCorrection"))
        }
    }

    private fun productionCallersOf(fixture: String): List<String> {
        val sources = mainSources()
        return sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "TrexData.kt" }
            .filter { it.readText().contains("$fixture(") }
            .map { it.relativeTo(sources).path.replace('\\', '/') }
            .sorted()
            .toList()
    }

    @Test
    fun theFlushThatSurvivesAConfigurationChangeIsSynchronous() {
        // A configuration change runs pause, stop and destroy inside one main-thread message, so
        // no frame is drawn and no coroutine launched from a lifecycle observer ever starts. Only
        // an onDispose body is guaranteed to run. If someone later "tidies" this into a
        // LaunchedEffect, every edit made inside the debounce window is lost on the next rotation
        // and the screen visibly reverts, because the plan and history are plain `remember`.
        val app = mainSources().resolve("com/example/trex_kotlin/TrexApp.kt").readText()

        assertTrue(
            "The disposal flush must be a DisposableEffect, not a coroutine",
            app.contains("DisposableEffect(store)") && app.contains("onDispose { store.save("),
        )
    }

    @Test
    fun theOnboardedFlagHasExactlyOneSourceOfTruth() {
        // Storing the flag beside its payload lets an instance-state bundle assert "onboarded"
        // over answers that were never flushed. The next snapshot cements that pair, and since the
        // screen never shows again the body metrics can never be collected a second time.
        val app = mainSources().resolve("com/example/trex_kotlin/TrexApp.kt").readText()

        assertTrue(
            "onboarded must be derived from the answers, not stored separately",
            app.contains("val onboarded = onboarding != null"),
        )
        assertFalse(
            "onboarded must not have its own saveable slot",
            app.contains("var onboarded by rememberSaveable"),
        )
    }

    @Test
    fun theFabricatedWorkoutHistoryHasNoProductionCallersLeft() {
        // seedWorkoutHistory invents a week by rotating today's plan over past dates. That was
        // harmless while nothing was durable; now that history is written to disk, any production
        // caller would persist the fiction as the user's own record on their first finished
        // session. It stays in the source as a test fixture only.
        assertTrue(
            "seedWorkoutHistory still has production callers: ${productionCallersOf("seedWorkoutHistory")}",
            productionCallersOf("seedWorkoutHistory").isEmpty(),
        )
    }

    @Test
    fun theFabricatedDietStillFeedsOnlyTheUnpersistedDietTab() {
        // Diet is deliberately out of this change: its records still die on a tab switch, so its
        // seeded meals cannot reach the disk. Pinned rather than ignored — the day diet gains
        // persistence, this fails and forces the same decision that was made for history above.
        assertEquals(listOf("com/example/trex_kotlin/MainScreens.kt"), productionCallersOf("seedFoods"))
    }

    @Test
    fun theStoreDirectoryIsExcludedFromCloudBackupOnBothApiEras() {
        // The store holds the body metrics onboarding collects. Auto-backup would copy them to a
        // Google account, which is the one thing this app tells the user it never does.
        //
        // Parsed rather than grepped: a substring check for the name and "<exclude" passes on a
        // file where the two are unrelated, or where the exclusion sits in <device-transfer> and
        // cloud backup is wide open. The attributes and the containing element are the rule.
        assertExcludesTheStoreDirectory("backup_rules.xml", "full-backup-content")
        assertExcludesTheStoreDirectory("data_extraction_rules.xml", "cloud-backup")
    }

    private fun assertExcludesTheStoreDirectory(fileName: String, parentTag: String) {
        val resources = listOf("src/main/res/xml", "app/src/main/res/xml")
            .map(::File)
            .firstOrNull(File::isDirectory)
            ?: error("Resource xml directory not found")

        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resources.resolve(fileName))

        val parents = document.getElementsByTagName(parentTag)
        assertEquals("$fileName needs exactly one <$parentTag>", 1, parents.length)

        val children = parents.item(0).childNodes
        val matching = (0 until children.length)
            .map(children::item)
            .filter { it.nodeName == "exclude" }
            .filter {
                it.attributes?.getNamedItem("domain")?.nodeValue == "file" &&
                    it.attributes?.getNamedItem("path")?.nodeValue == TREX_STORE_DIRECTORY_NAME
            }

        assertEquals(
            "$fileName must exclude domain=\"file\" path=\"$TREX_STORE_DIRECTORY_NAME\" inside <$parentTag>",
            1,
            matching.size,
        )
    }

    @Test
    fun theStoreWritesOnlyInsideTheExcludedDirectory() {
        // The exclusion above is only worth anything if every byte the store writes lands under
        // that directory — including the temp file a save writes through.
        val injection = mainSources()
            .resolve("com/example/trex_kotlin/store/RememberTrexStore.kt")
            .readText()

        assertTrue(
            "The store path must be built from the excluded directory constant",
            injection.contains("TREX_STORE_DIRECTORY_NAME"),
        )
        assertTrue(
            "The temp file must be a sibling of the store file, not of filesDir",
            mainSources()
                .resolve("com/example/trex_kotlin/store/FileTrexStore.kt")
                .readText()
                .contains("File(file.parentFile, file.name + TEMP_SUFFIX)"),
        )
    }
}
