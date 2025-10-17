/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2021 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.utils

import android.content.Context
import androidx.fragment.app.FragmentActivity
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.notifications.services.NtfyListenerService
import net.kourlas.voipms_sms.notifications.workers.NotificationsRegistrationWorker
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getNtfyTopic
import net.kourlas.voipms_sms.preferences.setSetupCompletedForVersion

/**
 * Returns a placeholder for F-Droid builds (no Firebase installation ID).
 */
fun getInstallationId(): String {
    return "F-Droid (ntfy.sh)"
}

/**
 * Subscribes to ntfy topics for the currently configured DIDs.
 * For F-Droid builds, this starts the NtfyListenerService instead of subscribing to FCM topics.
 */
fun subscribeToDidTopics(context: Context) {
    // Start ntfy service if notifications are enabled and topic is configured
    if (Notifications.getInstance(context).getNotificationsEnabled() && 
        getNtfyTopic(context).isNotBlank()) {
        NtfyListenerService.startService(context)
    }
}

/**
 * Attempt to enable push notifications using ntfy.sh for F-Droid builds.
 */
fun enablePushNotifications(
    context: Context,
    activityToShowError: FragmentActivity? = null
) {
    // Check if DIDs are configured and that notifications are enabled,
    // and silently quit if not
    if (!didsConfigured(context)
        || !Notifications.getInstance(context).getNotificationsEnabled()
    ) {
        setSetupCompletedForVersion(context, BuildConfig.VERSION_CODE.toLong())
        return
    }

    // Check if ntfy topic is configured
    val topic = getNtfyTopic(context)
    if (topic.isBlank()) {
        if (activityToShowError != null) {
            showSnackbar(
                activityToShowError, R.id.coordinator_layout,
                "Please configure ntfy topic in notification settings"
            )
        }
        setSetupCompletedForVersion(context, BuildConfig.VERSION_CODE.toLong())
        return
    }

    // Start ntfy service
    subscribeToDidTopics(context)

    // Start push notifications registration service
    NotificationsRegistrationWorker.registerForPushNotifications(context)
}