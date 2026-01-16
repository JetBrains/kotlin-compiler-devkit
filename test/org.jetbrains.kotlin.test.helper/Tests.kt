import org.jetbrains.kotlin.test.helper.asPathWithoutAllExtensions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class Tests {
    companion object {
        private val s = File.separatorChar
    }

    @Test
    fun testAsPathWithoutAllExtensions() {
        assertEquals("", "".asPathWithoutAllExtensions)
        assertEquals("example", "example.kt".asPathWithoutAllExtensions)
        assertEquals("example", "example.fir.kt".asPathWithoutAllExtensions)
        assertEquals("path${s}example", "path${s}example.fir.kt".asPathWithoutAllExtensions)
        assertEquals("path.2${s}to${s}example", "path.2${s}to${s}example.fir.kt".asPathWithoutAllExtensions)

        // Not sure why digit-based extensions are not considered as real extensions, but keep the existing behavior for now
        assertEquals("path${s}example.test.123", "path${s}example.test.123.kt".asPathWithoutAllExtensions)

        assertEquals("fileNameWoExtension", "fileNameWoExtension".asPathWithoutAllExtensions)
        assertEquals("path${s}fileNameWoExtension", "path${s}fileNameWoExtension".asPathWithoutAllExtensions)
        assertEquals("fileWithEmptyAfterDot", "fileWithEmptyAfterDot.ext.".asPathWithoutAllExtensions)
        assertEquals("fileWithEmptyAfterIntermediateDot", "fileWithEmptyAfterIntermediateDot..ext".asPathWithoutAllExtensions)
    }
}
