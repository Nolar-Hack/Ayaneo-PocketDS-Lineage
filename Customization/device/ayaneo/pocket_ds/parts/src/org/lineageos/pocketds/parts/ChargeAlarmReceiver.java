/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The alarm of a planned full charge (ChargeLimitController): receiving it starts the process if it
 * was not running, and the controller of the charge limit looks at the clock when it starts.
 */
public class ChargeAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // The application started the controllers: nothing else to do.
    }
}
