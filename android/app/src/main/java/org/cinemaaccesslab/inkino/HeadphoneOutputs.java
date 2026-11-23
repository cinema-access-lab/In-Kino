// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.media.AudioDeviceInfo;

/**
 * Which audio outputs count as personal listening devices, so that audio description is never
 * played out loud in a cinema. Bluetooth media (A2DP) and USB audio are accepted, because Android
 * can't tell headphones from speakers there; the player shows the device's name instead, so the
 * user can tell. Phone speakers, Bluetooth call audio, LE speakers, and broadcasts are rejected.
 */
final class HeadphoneOutputs {
    static final int NOT_PERSONAL = Integer.MAX_VALUE;

    private HeadphoneOutputs() {}

    /**
     * Lower is preferred. The order follows which output Android normally plays media through when
     * several are connected: wired before hearing aids, LE audio, Bluetooth, and USB.
     */
    static int rank(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return 0;
            case AudioDeviceInfo.TYPE_HEARING_AID: // Android 9 and later
                return 1;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return 2;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return 3;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return 4;
            default:
                return NOT_PERSONAL;
        }
    }

    static boolean isPersonal(int type) {
        return rank(type) != NOT_PERSONAL;
    }

    /** Whether the device's own name is more useful than its type, for example "WH-1000XM4". */
    static boolean hasUsefulName(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_HEARING_AID
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }
}
