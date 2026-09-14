package com.chenyc.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Creates and connects the OPPO/HeyMelody SPP socket.
 *
 * HeyMelody uses createRfcommSocketToServiceRecord(UUID). A fixed channel 15
 * path is also available for devices/ROMs where SDP fails.
 */
object OppoRfcommSocketFactory {
    private const val RFCOMM_CHANNEL = 15

    private val preferredUuids = listOf(
        UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5"),
        UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
    )

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    @Throws(IOException::class)
    fun connect(
        device: BluetoothDevice,
        logTag: String,
        connectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID
    ): BluetoothSocket {
        Log.d(logTag, "RFCOMM connection method: ${connectionMethod.preferenceValue}")
        return when (connectionMethod) {
            RfcommConnectionMethod.UUID -> connectViaUuids(device, logTag)
            RfcommConnectionMethod.CHANNEL -> connectViaChannel(device, logTag)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectViaUuids(device: BluetoothDevice, logTag: String): BluetoothSocket {
        val failures = mutableListOf<Exception>()

        for (uuid in preferredUuids) {
            val socket = try {
                Log.d(logTag, "Creating RFCOMM socket with UUID $uuid")
                device.createRfcommSocketToServiceRecord(uuid)
            } catch (e: Exception) {
                Log.w(logTag, "Failed to create RFCOMM socket with UUID $uuid", e)
                failures += e
                continue
            }

            tryConnect(socket, "UUID $uuid", logTag, failures)?.let { return it }
        }

        throw connectException("Unable to connect OPPO RFCOMM socket via UUID", failures)
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun connectViaChannel(device: BluetoothDevice, logTag: String): BluetoothSocket {
        val failures = mutableListOf<Exception>()
        val channelSocket = try {
            Log.d(logTag, "Creating RFCOMM socket with channel $RFCOMM_CHANNEL")
            val method = device.javaClass.getMethod(
                "createRfcommSocket",
                Int::class.javaPrimitiveType
            )
            method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
        } catch (e: Exception) {
            Log.w(logTag, "Failed to create channel RFCOMM socket", e)
            failures += e
            throw connectException("Unable to create OPPO RFCOMM socket via channel $RFCOMM_CHANNEL", failures)
        }

        return tryConnect(channelSocket, "channel $RFCOMM_CHANNEL", logTag, failures)
            ?: throw connectException("Unable to connect OPPO RFCOMM socket via channel $RFCOMM_CHANNEL", failures)
    }

    private fun tryConnect(
        socket: BluetoothSocket,
        label: String,
        logTag: String,
        failures: MutableList<Exception>
    ): BluetoothSocket? {
        return try {
            socket.connect()
            Log.d(logTag, "RFCOMM connected via $label")
            socket
        } catch (e: Exception) {
            Log.w(logTag, "RFCOMM connect failed via $label", e)
            failures += e
            try {
                socket.close()
            } catch (closeError: IOException) {
                Log.w(logTag, "Failed to close RFCOMM socket after $label failure", closeError)
            }
            null
        }
    }

    private fun connectException(message: String, failures: List<Exception>): IOException {
        val cause = failures.lastOrNull()
        val error = IOException(message, cause)
        failures.dropLast(1).forEach { error.addSuppressed(it) }
        return error
    }
}
