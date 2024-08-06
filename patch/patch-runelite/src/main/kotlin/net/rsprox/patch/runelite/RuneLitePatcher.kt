package net.rsprox.patch.runelite

import com.github.michaelbull.logging.InlineLogger
import net.lingala.zip4j.ZipFile
import net.rsprox.patch.PatchResult
import net.rsprox.patch.Patcher
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

@Suppress("DuplicatedCode", "SameParameterValue")
public class RuneLitePatcher : Patcher<Unit> {
    @OptIn(ExperimentalPathApi::class)
    override fun patch(
        path: Path,
        rsa: String,
        javConfigUrl: String,
        worldListUrl: String,
        port: Int,
        metadata: Unit,
    ): PatchResult {
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("Path $path does not point to a file.")
        }
        logger.debug { "Attempting to patch $path" }
        val time = System.currentTimeMillis()
        val outputFolder = path.parent.resolve("runelite-client-$time")
        val oldModulus: String
        val patchedJar: Path
        try {
            logger.debug { "Attempting to patch a jar." }
            Files.createDirectories(outputFolder)
            val inputFile = ZipFile(path.toFile())
            logger.debug { "Extracting existing classes from a zip file." }
            inputFile.extractAll(outputFolder.toFile().absolutePath)

            logger.debug { "Patching class files." }
            oldModulus = overwriteModulus(outputFolder, rsa)
            overwriteLocalHost(outputFolder)
            patchPort(outputFolder, port)
            patchedJar = path.parent.resolve(path.nameWithoutExtension + "-patched." + path.extension)
            val outputFile = ZipFile(patchedJar.toFile())
            val parentDir = outputFolder.toFile()
            val files = parentDir.walkTopDown().maxDepth(1)
            logger.debug { "Building a patched jar." }
            for (file in files) {
                if (file == parentDir) continue
                if (file.isFile) {
                    outputFile.addFile(file)
                } else {
                    outputFile.addFolder(file)
                }
            }
            outputFile.charset = inputFile.charset
        } finally {
            logger.debug { "Deleting temporary extracted class files." }
            outputFolder.deleteRecursively()
        }
        logger.debug { "Jar patching complete." }
        return PatchResult.Success(
            oldModulus,
            patchedJar,
        )
    }

    private fun patchPort(
        outputFolder: Path,
        port: Int,
    ) {
        val inputPort = toByteArray(listOf(43594 ushr 8 and 0xFF, 43594 and 0xFF))
        val outputPort = toByteArray(listOf(port ushr 8 and 0xFF, port and 0xFF))
        logger.debug { "Patching port from 43594 to $port in client.class" }
        val file = outputFolder.resolve("client.class").toFile()
        val bytes = file.readBytes()
        bytes.replaceBytes(inputPort, outputPort)
        file.writeBytes(bytes)
    }

    private fun toByteArray(list: List<Int>): ByteArray {
        return list.map(Int::toByte).toByteArray()
    }

    private fun ByteArray.replaceBytes(
        input: ByteArray,
        output: ByteArray,
    ) {
        val index = indexOf(input)
        check(index != -1) {
            "Unable to find byte sequence: ${input.contentToString()}"
        }
        overwrite(index, output)
    }

    private fun ByteArray.overwrite(
        index: Int,
        replacement: ByteArray,
    ) {
        for (i in replacement.indices) {
            this[i + index] = replacement[i]
        }
    }

    private fun overwriteModulus(
        outputFolder: Path,
        rsa: String,
    ): String {
        for (file in outputFolder.toFile().walkTopDown()) {
            if (!file.isFile) continue
            val bytes = file.readBytes()
            val index = bytes.indexOf("10001".toByteArray(Charsets.UTF_8))
            if (index == -1) {
                continue
            }
            logger.debug { "Attempting to patch modulus in class ${file.name}" }
            val (replacementBytes, oldModulus) =
                patchModulus(
                    bytes,
                    rsa,
                )
            file.writeBytes(replacementBytes)
            return oldModulus
        }
        throw IllegalStateException("Unable to find modulus.")
    }

    private fun overwriteLocalHost(outputFolder: Path) {
        for (file in outputFolder.toFile().walkTopDown()) {
            if (!file.isFile) continue
            val bytes = file.readBytes()
            val index = bytes.indexOf("127.0.0.1".toByteArray(Charsets.UTF_8))
            if (index == -1) continue
            logger.debug { "Patching localhost in file ${file.name}." }
            val new = patchLocalhost(bytes)
            file.writeBytes(new)
            return
        }
        throw IllegalStateException("Unable to find localhost.")
    }

    private fun patchModulus(
        bytes: ByteArray,
        replacement: String,
    ): Pair<ByteArray, String> {
        val sliceIndices =
            bytes.firstSliceIndices(0, 256) {
                isHex(it.toInt().toChar())
            }
        check(!isHex(bytes[sliceIndices.first - 1].toInt().toChar()))
        check(!isHex(bytes[sliceIndices.last + 1].toInt().toChar()))
        val slice = bytes.sliceArray(sliceIndices)
        val oldModulus = slice.toString(Charsets.UTF_8)
        val newModulus = replacement.toByteArray(Charsets.UTF_8)
        if (newModulus.size > slice.size) {
            throw IllegalStateException("New modulus cannot be larger than the old.")
        }
        val output = bytes.setString(sliceIndices.first, replacement)

        logger.debug { "Patched RSA modulus" }
        logger.debug { "Old modulus: $oldModulus" }
        logger.debug { "New modulus: $replacement" }
        return output to oldModulus
    }

    private fun patchLocalhost(bytes: ByteArray): ByteArray {
        // Rather than only accept the localhost below
        val searchInput = "127.0.0.1"
        // Due to the Java client using "endsWith" function, we can't set any string here
        val replacement = ""

        val newSet = replaceText(bytes, searchInput, replacement)
        logger.debug { "Replaced localhost from $searchInput to $replacement" }
        return newSet
    }

    private fun replaceText(
        bytes: ByteArray,
        input: String,
        replacement: String,
    ): ByteArray {
        require(replacement.length <= input.length) {
            "Replacement string cannot be longer than the input"
        }
        val searchBytes = input.toByteArray(Charsets.UTF_8)
        val index = bytes.indexOf(searchBytes)
        if (index == -1) {
            throw IllegalArgumentException("Unable to locate input $input")
        }
        return bytes.setString(index, replacement)
    }

    private fun ByteArray.setString(
        stringStartIndex: Int,
        replacementString: String,
    ): ByteArray {
        val oldLenByte1 = this[stringStartIndex - 2].toInt() and 0xFF
        val oldLenByte2 = this[stringStartIndex - 1].toInt() and 0xFF
        val oldLength = (oldLenByte1 shl 8) or oldLenByte2
        val lengthDelta = replacementString.length - oldLength
        val replacement = ByteArray(size + lengthDelta)

        // Fill in the bytes right up until the length of the string (unmodified)
        copyInto(replacement, 0, 0, stringStartIndex - 2)

        // Fill in the length of the replacement string
        check(replacementString.length in 0..<0xFFFF)
        val newSizeByte1 = replacementString.length ushr 8 and 0xFF
        val newSizeByte2 = replacementString.length and 0xFF
        replacement[stringStartIndex - 2] = newSizeByte1.toByte()
        replacement[stringStartIndex - 1] = newSizeByte2.toByte()

        // Fill in the actual replacement string itself
        val replacementBytes = replacementString.toByteArray(Charsets.UTF_8)
        for (i in replacementBytes.indices) {
            replacement[stringStartIndex + i] = replacementBytes[i]
        }

        // Fill in the trailing bytes that come after the string (unmodified)
        copyInto(
            replacement,
            stringStartIndex + replacementString.length,
            stringStartIndex + oldLength,
        )
        return replacement
    }

    private fun ByteArray.firstSliceIndices(
        startIndex: Int,
        length: Int = -1,
        condition: (Byte) -> Boolean,
    ): IntRange {
        var start = startIndex
        val size = this.size
        while (true) {
            // First locate the starting index where a byte is being accepted
            while (start < size) {
                val byte = this[start]
                if (condition(byte)) {
                    break
                }
                start++
            }
            var end = start + 1
            // Now find the end index where a byte is not being accepted
            while (end < size) {
                val byte = this[end]
                if (!condition(byte)) {
                    break
                }
                end++
            }
            if (length != -1 && end - start < length) {
                start = end
                continue
            }
            return start..<end
        }
    }

    private fun ByteArray.indexOf(
        search: ByteArray,
        startIndex: Int = 0,
    ): Int {
        require(search.isNotEmpty()) {
            "Bytes to search are empty"
        }
        require(startIndex >= 0) {
            "Start index is negative"
        }
        var matchOffset = 0
        var start = startIndex
        var offset = startIndex
        val size = size
        while (offset < size) {
            if (this[offset] == search[matchOffset]) {
                if (matchOffset++ == 0) {
                    start = offset
                }
                if (matchOffset == search.size) {
                    return start
                }
            } else {
                matchOffset = 0
            }
            offset++
        }
        return -1
    }

    private fun isHex(char: Char): Boolean {
        return char in lowercaseHexStringCharRange ||
            char in uppercaseHexStringCharRange ||
            char in hexDigitsCharRange
    }

    private companion object {
        private val lowercaseHexStringCharRange = 'a'..'f'
        private val uppercaseHexStringCharRange = 'A'..'F'
        private val hexDigitsCharRange = '0'..'9'
        private val logger = InlineLogger()
    }
}
