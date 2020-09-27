/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2020 Michael Kourlas
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

package net.kourlas.voipms_sms;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;

import net.kourlas.voipms_sms.network.NetworkManager;
import net.kourlas.voipms_sms.sms.ConversationId;
import net.kourlas.voipms_sms.sms.Database;
import net.kourlas.voipms_sms.sms.workers.SyncWorker;

import java.util.HashMap;
import java.util.Map;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.work.ExistingWorkPolicy;
import okhttp3.OkHttpClient;

import static net.kourlas.voipms_sms.preferences.PreferencesKt.getAppTheme;
import static net.kourlas.voipms_sms.preferences.fragments.AppearancePreferencesFragment.DARK;
import static net.kourlas.voipms_sms.preferences.fragments.AppearancePreferencesFragment.LIGHT;
import static net.kourlas.voipms_sms.preferences.fragments.AppearancePreferencesFragment.SYSTEM_DEFAULT;
import static net.kourlas.voipms_sms.utils.ExceptionsKt.logException;
import static net.kourlas.voipms_sms.utils.FcmKt.subscribeToDidTopics;

/**
 * Custom application implementation that keeps track of visible activities.
 * <p>
 * Kotlin doesn't seem to work at this level in older versions of Android, so
 * this class is implemented in plain old Java.
 */
public class CustomApplication extends Application {
    private static CustomApplication instance;
    private int conversationsActivitiesVisible = 0;
    private final Map<ConversationId, Integer> conversationActivitiesVisible =
        new HashMap<>();
    private OkHttpClient okHttpClient;

    public static CustomApplication getInstance() {
        return instance;
    }

    @SuppressWarnings("UnusedReturnValue")
    public boolean conversationsActivityVisible() {
        return conversationsActivitiesVisible > 0;
    }

    public void conversationsActivityIncrementCount() {
        conversationsActivitiesVisible++;
    }

    public void conversationsActivityDecrementCount() {
        conversationsActivitiesVisible--;
    }

    @SuppressWarnings("UnusedReturnValue")
    public boolean conversationActivityVisible(ConversationId conversationId) {
        Integer count = conversationActivitiesVisible.get(conversationId);
        return count != null && count > 0;
    }

    public void conversationActivityIncrementCount(
        ConversationId conversationId) {
        Integer count = conversationActivitiesVisible.get(conversationId);
        if (count == null) {
            count = 0;
        }
        conversationActivitiesVisible.put(conversationId, count + 1);
    }

    public void conversationActivityDecrementCount(
        ConversationId conversationId) {
        Integer count = conversationActivitiesVisible.get(conversationId);
        if (count == null) {
            count = 0;
        }
        conversationActivitiesVisible.put(conversationId, count - 1);
    }

    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        okHttpClient = new OkHttpClient();

        // Update theme
        String theme = getAppTheme(getApplicationContext());
        switch (theme) {
            case SYSTEM_DEFAULT:
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case LIGHT:
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case DARK:
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }

        // Register for network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                RuntimeException e = new RuntimeException(
                    "Connectivity manager does not exist");
                logException(e);
                throw e;
            }
            connectivityManager.registerDefaultNetworkCallback(
                NetworkManager.Companion.getInstance());
        }

        // Open database
        Database.Companion.getInstance(getApplicationContext());

        // Subscribe to topics for current DIDs
        subscribeToDidTopics(getApplicationContext());

        // We want to start the periodic synchronization if it hasn't
        // already
        SyncWorker.Companion.startPeriodicWorker(
            this, /*existingWorkPolicy=*/ExistingWorkPolicy.KEEP);
    }
}
