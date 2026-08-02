package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.FileCopyBlock
import dev.pleiades.masamune.flow.runtime.impl.FileDeleteBlock
import dev.pleiades.masamune.flow.runtime.impl.FileExistsBlock
import dev.pleiades.masamune.flow.runtime.impl.FileListBlock
import dev.pleiades.masamune.flow.runtime.impl.FileMakeDirectoryBlock
import dev.pleiades.masamune.flow.runtime.impl.FileMoveBlock
import dev.pleiades.masamune.flow.runtime.impl.FileReadBlock
import dev.pleiades.masamune.flow.runtime.impl.FileWriteBlock
import dev.pleiades.masamune.flow.runtime.impl.ZipCompressBlock
import dev.pleiades.masamune.flow.runtime.impl.ZipExtractBlock
import dev.pleiades.masamune.flow.runtime.impl.ZipListBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit proof that the Storage file/zip blocks actually move bytes on a real filesystem — run
 * against a JUnit [TemporaryFolder], not a device, because that is exactly what makes this subset
 * the honestly-testable one. Each test drives a block the way the runtime does (an args map of
 * resolved [Value]s, a [FlowNode] carrying the output bindings) and asserts on the [Outcome] *and*
 * on the bytes on disk.
 */
class StorageBlocksTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun node(vararg outputs: Pair<String, String>) =
        FlowNode("n", "spec", 0f, 0f, outputs = outputs.toMap())

    private fun path(name: String) = File(tmp.root, name).path

    // ------------------------------------------------------------------ read / write

    @Test fun writeThenReadRoundTrips() = runTest {
        val p = path("a/b/note.txt")
        val w = FileWriteBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("path" to Value.Text(p), "content" to Value.Text("héllo")),
        )
        assertTrue(w is Outcome.Proceed)
        assertEquals("héllo", File(p).readText())

        val r = FileReadBlock().run(
            Fiber("f", "flow"), node("varContent" to "out"),
            mapOf("path" to Value.Text(p)),
        )
        assertEquals(Value.Text("héllo"), (r as Outcome.Proceed).writes["out"])
    }

    @Test fun appendFlagAsFalseConstantTruncates() = runTest {
        // The regression the asFlag() helper exists for: append="false" is a Value.Text whose
        // isTrue is true. If the block read isTrue it would append; it must truncate.
        val p = path("log.txt")
        File(p).writeText("old")
        val out = FileWriteBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("path" to Value.Text(p), "content" to Value.Text("new"), "append" to Value.Text("false")),
        )
        assertTrue(out is Outcome.Proceed)
        assertEquals("new", File(p).readText())
    }

    @Test fun appendFlagAsTrueAppends() = runTest {
        val p = path("log.txt")
        File(p).writeText("old")
        FileWriteBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("path" to Value.Text(p), "content" to Value.Text("+new"), "append" to Value.Text("true")),
        )
        assertEquals("old+new", File(p).readText())
    }

    @Test fun readMissingFileFailsVisibly() = runTest {
        val out = FileReadBlock().run(
            Fiber("f", "flow"), node("varContent" to "out"),
            mapOf("path" to Value.Text(path("nope.txt"))),
        )
        assertTrue(out is Outcome.Fail)
    }

    @Test fun writeUnknownDecodeModeFails() = runTest {
        val out = FileWriteBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("path" to Value.Text(path("x")), "content" to Value.Text("z"), "decode" to Value.Text("base64")),
        )
        assertTrue(out is Outcome.Fail)
        assertTrue((out as Outcome.Fail).message.contains("decode"))
    }

    // ------------------------------------------------------------------ exists

    @Test fun existsReportsTypeSizeAndMissingTakesNo() = runTest {
        val p = path("f.txt")
        File(p).writeText("1234")
        val yes = FileExistsBlock().run(
            Fiber("f", "flow"),
            node("varType" to "t", "varSize" to "s"),
            mapOf("path" to Value.Text(p)),
        )
        assertEquals(Port.YES, (yes as Outcome.Proceed).port)
        assertEquals(Value.Text("file"), yes.writes["t"])
        assertEquals(Value.Num(4.0), yes.writes["s"])

        val no = FileExistsBlock().run(
            Fiber("f", "flow"), node(), mapOf("path" to Value.Text(path("gone"))),
        )
        assertEquals(Port.NO, (no as Outcome.Proceed).port)
    }

    // ------------------------------------------------------------------ mkdir / delete

    @Test fun makeDirectoryIsIdempotentButRefusesAFile() = runTest {
        val d = path("dir")
        assertTrue(FileMakeDirectoryBlock().run(Fiber("f", "flow"), node(), mapOf("path" to Value.Text(d))) is Outcome.Proceed)
        assertTrue(File(d).isDirectory)
        // second time: still OK
        assertTrue(FileMakeDirectoryBlock().run(Fiber("f", "flow"), node(), mapOf("path" to Value.Text(d))) is Outcome.Proceed)
        // a file where a dir is asked for: Fail
        val fp = path("file")
        File(fp).writeText("x")
        assertTrue(FileMakeDirectoryBlock().run(Fiber("f", "flow"), node(), mapOf("path" to Value.Text(fp))) is Outcome.Fail)
    }

    @Test fun deleteNonEmptyDirNeedsRecursive() = runTest {
        val d = File(tmp.root, "tree").apply { mkdirs() }
        File(d, "child.txt").writeText("x")
        val refused = FileDeleteBlock().run(
            Fiber("f", "flow"), node(), mapOf("path" to Value.Text(d.path)),
        )
        assertTrue(refused is Outcome.Fail)
        assertTrue(d.exists())
        val ok = FileDeleteBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("path" to Value.Text(d.path), "recursive" to Value.Text("true")),
        )
        assertTrue(ok is Outcome.Proceed)
        assertFalse(d.exists())
    }

    @Test fun deleteMissingIsSuccess() = runTest {
        val out = FileDeleteBlock().run(Fiber("f", "flow"), node(), mapOf("path" to Value.Text(path("ghost"))))
        assertTrue(out is Outcome.Proceed)
    }

    // ------------------------------------------------------------------ copy / move / list

    @Test fun copyDirNeedsRecursiveThenCopiesTree() = runTest {
        val src = File(tmp.root, "src").apply { mkdirs() }
        File(src, "a.txt").writeText("A")
        File(src, "sub").apply { mkdirs() }
        File(src, "sub/b.txt").writeText("B")
        val dst = File(tmp.root, "dst")

        val refused = FileCopyBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("sourcePath" to Value.Text(src.path), "targetPath" to Value.Text(dst.path)),
        )
        assertTrue(refused is Outcome.Fail)

        val ok = FileCopyBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("sourcePath" to Value.Text(src.path), "targetPath" to Value.Text(dst.path), "recursive" to Value.Text("true")),
        )
        assertTrue(ok is Outcome.Proceed)
        assertEquals("A", File(dst, "a.txt").readText())
        assertEquals("B", File(dst, "sub/b.txt").readText())
    }

    @Test fun moveRenamesAndRemovesOriginal() = runTest {
        val src = File(tmp.root, "m.txt").apply { writeText("data") }
        val dst = File(tmp.root, "moved/m.txt")
        val out = FileMoveBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("sourcePath" to Value.Text(src.path), "targetPath" to Value.Text(dst.path)),
        )
        assertTrue(out is Outcome.Proceed)
        assertFalse(src.exists())
        assertEquals("data", dst.readText())
    }

    @Test fun listReturnsAbsolutePathsRecursively() = runTest {
        val d = File(tmp.root, "ls").apply { mkdirs() }
        File(d, "one.txt").writeText("1")
        File(d, "sub").apply { mkdirs() }
        File(d, "sub/two.txt").writeText("2")
        val out = FileListBlock().run(
            Fiber("f", "flow"), node("varFiles" to "files"),
            mapOf("path" to Value.Text(d.path), "recursive" to Value.Text("true"), "types" to Value.Text("files")),
        )
        val files = ((out as Outcome.Proceed).writes["files"] as Value.ArrayV).items.map { (it as Value.Text).value }
        assertTrue(files.any { it.endsWith("one.txt") })
        assertTrue(files.any { it.endsWith("sub/two.txt") || it.endsWith("sub${File.separator}two.txt") })
        assertTrue(files.all { File(it).isAbsolute })
    }

    // ------------------------------------------------------------------ zip

    @Test fun zipCompressListExtractRoundTrips() = runTest {
        val src = File(tmp.root, "z").apply { mkdirs() }
        File(src, "hello.txt").writeText("world")
        File(src, "deep").apply { mkdirs() }
        File(src, "deep/x.txt").writeText("X")
        val zip = File(tmp.root, "out.zip")

        val c = ZipCompressBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("zipFile" to Value.Text(zip.path), "sourcePath" to Value.Text(src.path), "recursive" to Value.Text("true")),
        )
        assertTrue(c is Outcome.Proceed)
        assertTrue(zip.isFile)

        val l = ZipListBlock().run(
            Fiber("f", "flow"), node("varFiles" to "names"),
            mapOf("zipFile" to Value.Text(zip.path)),
        )
        val names = ((l as Outcome.Proceed).writes["names"] as Value.ArrayV).items.map { (it as Value.Text).value }
        assertTrue(names.any { it.endsWith("hello.txt") })
        assertTrue(names.any { it.endsWith("deep/x.txt") })

        val dst = File(tmp.root, "unz")
        val e = ZipExtractBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("zipFile" to Value.Text(zip.path), "targetPath" to Value.Text(dst.path)),
        )
        assertTrue(e is Outcome.Proceed)
        assertEquals("world", File(dst, "hello.txt").readText())
        assertEquals("X", File(dst, "deep/x.txt").readText())
    }

    @Test fun zipExtractRefusesZipSlip() = runTest {
        // Craft a malicious archive whose entry escapes the destination.
        val zip = File(tmp.root, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../escaped.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        val dst = File(tmp.root, "safe")
        val out = ZipExtractBlock().run(
            Fiber("f", "flow"), node(),
            mapOf("zipFile" to Value.Text(zip.path), "targetPath" to Value.Text(dst.path)),
        )
        assertTrue(out is Outcome.Fail)
        assertTrue((out as Outcome.Fail).message.contains("zip-slip"))
        assertFalse(File(tmp.root, "escaped.txt").exists())
    }
}
