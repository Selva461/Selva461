package com.selva.chargefix

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same shape as android.hardware.usb.ParcelableUsbPort on Android 12-14: private mId, no getId(). */
@Suppress("unused")
private class FakeParcelableUsbPort(private val mId: String, private val mSupportedModes: Int)

@Suppress("unused")
private class FakeWithGetter { fun getId() = "port7" }

class UsbOpsTest {
    @Test fun readsPrivateIdFieldWhenThereIsNoGetter() {
        assertEquals("port0", UsbOps.portId(FakeParcelableUsbPort("port0", 15)))
    }

    @Test fun usesGetterWhenPresent() {
        assertEquals("port7", UsbOps.portId(FakeWithGetter()))
    }
}
