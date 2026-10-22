/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

/** Entry of Settings > Battery: opens the {@link SectionActivity} category POWER_OFF. */
public class PowerOffEntry extends SectionActivity {
    @Override
    protected String section() {
        return POWER_OFF;
    }
}
