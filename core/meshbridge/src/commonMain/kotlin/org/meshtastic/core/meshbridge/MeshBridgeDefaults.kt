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
package org.meshtastic.core.meshbridge

object MeshBridgeDefaults {
    /** Default loopback port for the Mesh Bridge TCP server. */
    const val DEFAULT_PORT = 8090

    /** The only Meshtastic port number bridged by this server: SIMULATOR_APP (69). */
    const val BRIDGE_PORTNUM = 69

    /** Maximum allowed payload size in bytes (LoRa MTU / Data proto limit safety cap). */
    const val MAX_PAYLOAD_BYTES = 220

    /** Maximum allowed incoming frame length in bytes (guard against malformed or runaway streams). */
    const val MAX_FRAME_BYTES = 1024

    /** Maximum number of simultaneous local TCP client connections. */
    const val MAX_CLIENTS = 4

    /** Header size in bytes of a Server -> Client frame (u32 length + u32 from + u32 to minus length field). */
    const val SERVER_FRAME_HEADER_BYTES = 8

    /** 32-bit unsigned mask. */
    const val UINT32_MASK = 0xFFFF_FFFFL

    /** Magic byte indicating a game envelope payload ('G' = 0x47). */
    const val MAGIC_ENVELOPE: Byte = 0x47

    /** Magic byte indicating a presence announcement payload ('P' = 0x50). */
    const val MAGIC_PRESENCE: Byte = 0x50

    /** Byte offset of the client `from` u32 LE in the client payload wrapper. */
    const val WRAPPER_OFFSET_FROM = 0

    /** Byte offset of the recipient `to` u32 LE in the client payload wrapper. */
    const val WRAPPER_OFFSET_TO = 4

    /** Byte offset of the body start in the client payload wrapper. */
    const val WRAPPER_OFFSET_BODY = 8

    /** Minimum wrapper size in bytes to contain header (from + to) and at least 1 body byte. */
    const val MIN_ROUTABLE_WRAPPER_BYTES = 9

    /** Minimum wrapper size in bytes to contain the client header (from + to). */
    const val MIN_WRAPPER_HEADER_BYTES = 8

    /** Maximum capacity of the LRU peer cache (clientId -> LoRa node number). */
    const val MAX_PEER_CACHE_SIZE = 32

    /** Hexadecimal radix for formatting client IDs and node numbers in log messages. */
    const val HEX_RADIX = 16
}
