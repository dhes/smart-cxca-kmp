/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package health.hopena.cxca.demo.flow

import smart_cxca_kmp.cxca_demo.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Reads a bundled knowledge artifact, by path relative to `composeResources` (e.g. `files/pd/…`).
 */
typealias AssetReader = suspend (path: String) -> String

@OptIn(ExperimentalResourceApi::class)
val bundledAssets: AssetReader = { path -> Res.readBytes(path).decodeToString() }
