package app.worbus

import java.io.File
import kotlin.test.assertTrue

/**
 * A file or directory a source-reading test reads, by its path from composeApp/ (where the host
 * tests run). It must exist: a path that went stale (a package move, a renamed file) fails the test
 * here instead of being walked as an empty tree that holds no offender (Worbus P5, Review Focus 4).
 */
fun sourceFile(path: String): File {
	val file = File(path)
	assertTrue(file.exists(), "${file.absolutePath} not found (host tests run from composeApp/)")
	return file
}
