/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

/** Entry of Settings > Battery: opens the {@link SectionActivity} category BATTERY. */
public class BatteryRadiosEntry extends SectionActivity {
    @Override
    protected String section() {
        return BATTERY;
    }
}
