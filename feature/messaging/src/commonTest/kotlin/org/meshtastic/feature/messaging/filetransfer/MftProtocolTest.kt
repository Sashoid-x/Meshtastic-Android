/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.messaging.filetransfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MftProtocolTest {

    @Test
    fun isMftPacket_identifiesValidMagic() {
        val start =
            MftStart(
                transferId = 123,
                fileSize = 1000L,
                totalChunks = 5,
                crc32 = 0x12345678L,
                fileName = "test.txt",
                isGzip = true,
            )
        val encoded = start.encode()

        assertTrue(MftProtocol.isMftPacket(encoded))
        assertEquals(MftProtocol.TYPE_START, MftProtocol.packetType(encoded))
    }

    @Test
    fun isMftPacket_rejectsInvalidMagicOrShortPacket() {
        assertFalse(MftProtocol.isMftPacket(byteArrayOf()))
        assertFalse(MftProtocol.isMftPacket(byteArrayOf(0x01, 0x02, 0x03)))
        assertFalse(MftProtocol.isMftPacket(byteArrayOf(0x4D, 0x46, 0x54, 0x02))) // wrong version
    }

    @Test
    fun mftStart_encodeAndDecodeRoundTrip() {
        val original =
            MftStart(
                transferId = 42,
                fileSize = 12345L,
                totalChunks = 62,
                crc32 = 0xCAFEBABE,
                fileName = "document.pdf",
                isGzip = true,
            )

        val encoded = original.encode()
        val decoded = MftStart.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.fileSize, decoded.fileSize)
        assertEquals(original.totalChunks, decoded.totalChunks)
        assertEquals(original.crc32, decoded.crc32)
        assertEquals(original.fileName, decoded.fileName)
        assertEquals(original.isGzip, decoded.isGzip)
    }

    @Test
    fun mftStart_decodeRejectsMalformedPayload() {
        assertNull(MftStart.decode(byteArrayOf(0x4D, 0x46, 0x54, 0x01, 0x01)))
    }

    @Test
    fun mftData_encodeAndDecodeRoundTrip() {
        val chunkData = byteArrayOf(1, 2, 3, 4, 5, 42, -1, -128)
        val original = MftData(transferId = 77, chunkIndex = 3, data = chunkData)

        val encoded = original.encode()
        val decoded = MftData.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.chunkIndex, decoded.chunkIndex)
        assertEquals(original.data.toList(), decoded.data.toList())
    }

    @Test
    fun mftStartAck_encodeAndDecodeRoundTrip() {
        val original = MftStartAck(transferId = 123, cachedChunksCount = 15)

        val encoded = original.encode()
        val decoded = MftStartAck.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.cachedChunksCount, decoded.cachedChunksCount)
    }

    @Test
    fun mftPassEnd_encodeAndDecodeRoundTrip() {
        val original = MftPassEnd(transferId = 456, passNumber = 2)

        val encoded = original.encode()
        val decoded = MftPassEnd.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.passNumber, decoded.passNumber)
    }

    @Test
    fun mftMissing_encodeAndDecodeRoundTrip() {
        val missing = listOf(3, 7, 15, 42, 99)
        val original = MftMissing(transferId = 789, passNumber = 2, totalMissing = 5, missingIndices = missing)

        val encoded = original.encode()
        val decoded = MftMissing.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.passNumber, decoded.passNumber)
        assertEquals(original.totalMissing, decoded.totalMissing)
        assertEquals(original.missingIndices, decoded.missingIndices)
    }

    @Test
    fun mftComplete_encodeAndDecodeRoundTrip() {
        val original = MftComplete(transferId = 999)

        val encoded = original.encode()
        val decoded = MftComplete.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
    }

    @Test
    fun mftCancel_encodeAndDecodeRoundTrip() {
        val original = MftCancel(transferId = 555, reason = MftProtocol.CANCEL_USER)

        val encoded = original.encode()
        val decoded = MftCancel.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.reason, decoded.reason)
    }

    @Test
    fun computeCrc32_producesCorrectChecksum() {
        val testData = "123456789".encodeToByteArray()
        val crc = computeCrc32(testData)
        assertEquals(0xCBF43926L, crc)
    }

    @Test
    fun mftCompression_roundTripAndEfficiency() {
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(20)
        val rawBytes = text.encodeToByteArray()

        val (compressed, isCompressed) = MftCompression.compress(rawBytes)
        assertTrue(isCompressed)
        assertTrue(compressed.size < rawBytes.size)

        val decompressed = MftCompression.decompress(compressed)
        assertEquals(rawBytes.toList(), decompressed.toList())
    }

    @Test
    fun mftProgress_encodeAndDecodeRoundTrip() {
        val original = MftProgress(transferId = 1234, receivedChunks = 45, totalChunks = 60)

        val encoded = original.encode()
        val decoded = MftProgress.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.receivedChunks, decoded.receivedChunks)
        assertEquals(original.totalChunks, decoded.totalChunks)

        // General MftPacket decode
        val packet = MftPacket.decode(encoded)
        assertTrue(packet is MftProgress)
        assertEquals(45, packet.receivedChunks)
    }

    @Test
    fun progressReportInterval_calculatesCorrectIntervals() {
        assertEquals(0, MftProtocol.progressReportInterval(5))
        assertEquals(5, MftProtocol.progressReportInterval(15))
        assertEquals(10, MftProtocol.progressReportInterval(40))
        assertEquals(15, MftProtocol.progressReportInterval(80))
        assertEquals(20, MftProtocol.progressReportInterval(150))
    }
}
