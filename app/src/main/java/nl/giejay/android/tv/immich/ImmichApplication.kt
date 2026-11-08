/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.giejay.android.tv.immich

import android.app.Application
import android.content.Context
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/** Self-hosted application. No analytics, crash upload, background relay or billing SDK. */
class ImmichApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        PreferenceManager.init(this)
        // Timber has no planted trees: metadata and credentials never go to Logcat or a remote sink.
    }
    companion object { var appContext: Context? = null }
}
