package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBluetoothWatchDeviceTest {
    @Test
    fun directBluetoothDeviceUsesItsAddressForReaderPreview() {
        val device = PhoneBluetoothWatchDevice(
            name = "Watch",
            address = "AA:BB:CC:DD:EE:FF",
            uuidCount = 1
        )

        assertEquals("AA:BB:CC:DD:EE:FF", device.readerPreviewAddress)
    }

    @Test
    fun ipUpgradedDeviceKeepsBluetoothAddressForReaderPreview() {
        val device = PhoneBluetoothWatchDevice(
            name = "Watch (wifi)",
            address = "ip:watch-device-id",
            uuidCount = 1,
            remoteDeviceId = "watch-device-id",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals("AA:BB:CC:DD:EE:FF", device.readerPreviewAddress)
    }
}
