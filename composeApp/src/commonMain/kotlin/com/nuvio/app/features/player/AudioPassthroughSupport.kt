package com.nuvio.app.features.player

/**
 * Encodings the connected device accepts as a bitstream (AC-3, E-AC-3 …).
 * An empty list means the device cannot play surround sound as a bitstream.
 */
expect fun audioPassthroughSupportedEncodings(): List<String>
