package org.schabi.parakeetype.audio

/**
 * Thrown by [AudioCaptureManager.startCapture] when another app (e.g. a phone call) has the
 * microphone, so Parakeetype cannot record.
 */
class MicrophoneBusyException(message: String) : Exception(message)
