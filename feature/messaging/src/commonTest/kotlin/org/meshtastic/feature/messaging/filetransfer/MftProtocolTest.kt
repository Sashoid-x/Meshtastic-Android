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
            MftStart(transferId = 123, fileSize = 1000L, totalChunks = 5, crc32 = 0x12345678L, fileName = "test.txt")
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
            )

        val encoded = original.encode()
        val decoded = MftStart.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.fileSize, decoded.fileSize)
        assertEquals(original.totalChunks, decoded.totalChunks)
        assertEquals(original.crc32, decoded.crc32)
        assertEquals(original.fileName, decoded.fileName)
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
    fun mftAck_encodeAndDecodeRoundTrip() {
        val original = MftAck(transferId = 999, chunkIndex = 14, status = MftProtocol.ACK_OK)

        val encoded = original.encode()
        val decoded = MftAck.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.chunkIndex, decoded.chunkIndex)
        assertEquals(original.status, decoded.status)
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
}
