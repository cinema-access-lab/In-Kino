// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;

public class HeadphoneOutputsTest {
    @Test
    public void personalOutputsAreAccepted() {
        assertTrue(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_WIRED_HEADPHONES));
        assertTrue(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertTrue(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_BLE_HEADSET));
        assertTrue(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_HEARING_AID));
        assertTrue(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_USB_DEVICE));
    }

    @Test
    public void speakersAndCallAudioAreRejected() {
        assertFalse(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertFalse(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE));
        assertFalse(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertFalse(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_BLE_SPEAKER));
        assertFalse(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_BLE_BROADCAST));
        assertFalse(HeadphoneOutputs.isPersonal(AudioDeviceInfo.TYPE_HDMI));
    }

    @Test
    public void wiredIsPreferredOverBluetooth() {
        assertTrue(
                HeadphoneOutputs.rank(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
                        < HeadphoneOutputs.rank(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertTrue(
                HeadphoneOutputs.rank(AudioDeviceInfo.TYPE_HEARING_AID)
                        < HeadphoneOutputs.rank(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
    }
}
