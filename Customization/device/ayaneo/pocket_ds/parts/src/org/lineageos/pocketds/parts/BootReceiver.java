/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the app at boot (before and after the first unlock) and after it was updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // The process is already up (the application started the controllers): keep it alive.
        context.startForegroundService(new Intent(context, KeepAliveService.class));
    }
}
