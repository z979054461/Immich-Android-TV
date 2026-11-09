#!/usr/bin/env python3
"""Fail closed on known runtime privacy regressions. No network or build required.

This source/configuration check is not a substitute for release APK inspection,
loopback networking tests, or device packet-capture testing.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
KOTLIN = APP / "java/nl/giejay/android/tv/immich"
MEDIA = ROOT / "mediaslider/mediaslider/src/main"
failures = []
checks = 0


def check(ok, message):
    global checks
    checks += 1
    if not ok:
        failures.append(message)


def text(path):
    check(path.is_file(), f"Missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def without_comments(source):
    # Strip line comments only at line start, so https:// string literals survive.
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", source)


banned = {
    "telemetry SDK": r"\b(?:Firebase\w*|CrashReportingTree)\b|com\.google\.firebase",
    "billing SDK": r"com\.android\.billingclient|\bBillingClient\b",
    "Google identity SDK": r"com\.google\.android\.gms\.auth",
    "phone pairing/author relay": r"immich\.giejay\.nl|\bAuthenticationClient\b|\bAuthByPhoneFragment\b|R\.string\.authentication_url",
    "QR relay generator": r"customqrgenerator|QrCodeGenerator|QrData\.Url",
    "trust-all TLS": r"UnsafeOkHttpClient|\.sslSocketFactory\s*\(|\.hostnameVerifier\s*\(|checkServerTrusted\s*\(",
    "credential in URL": r"[?&](?:apiKey|api_key)=",
    "external media intent": r"Intent\.ACTION_VIEW",
    "unguarded HTTP datasource": r"DefaultHttpDataSource",
    "response body logger": r"ResponseLoggingInterceptor",
}
for root in (APP, MEDIA):
    for path in sorted(root.rglob("*")):
        if path.suffix not in (".kt", ".java"):
            continue
        source = without_comments(path.read_text(encoding="utf-8"))
        for reason, pattern in banned.items():
            check(not re.search(pattern, source, re.I if reason == "credential in URL" else 0),
                  f"{path.relative_to(ROOT)}: prohibited {reason}")
        if re.search(r"OkHttpClient\s*\.\s*Builder\s*\(", source):
            check(path in (KOTLIN / "api/ApiClientFactory.kt", KOTLIN / "update/UpdateRepository.kt"),
                  f"{path.relative_to(ROOT)}: HTTP client created outside guarded factory")
        check(not re.search(r"OkHttpUrlLoader\.Factory\s*\(\s*\)", source),
              f"{path.relative_to(ROOT)}: unguarded Glide fallback")
        check(not re.search(r"(?:URL\s*\(|\.openConnection\s*\(|\bSocket\s*\(|WebView|DownloadManager)", source),
              f"{path.relative_to(ROOT)}: alternate network stack needs explicit audit")

for relative in ("timeline/TimelineFocusVideoPreview.kt", "slider/ImmichMediaSlider.kt",
                 "screensaver/ScreenSaverPreviewFragment.kt"):
    source = without_comments(text(KOTLIN / relative))
    check("PrivateMediaDataSource.factory()" in source,
          f"{relative}: media must use PrivateMediaDataSource.factory()")

factory = without_comments(text(KOTLIN / "api/ApiClientFactory.kt"))
for required in (".followRedirects(false)", ".followSslRedirects(false)",
                 ".addInterceptor(", "ServerPolicy.requireAllowed", "ServerPolicy.isValidApiKey"):
    check(required in factory, f"ApiClientFactory missing required guard: {required}")
session = factory.split("fun forSession(", 1)[-1].split("fun forImages(", 1)[0]
images = factory.split("fun forImages(", 1)[-1].split("fun getClient(", 1)[0]
raw_client = factory.split("fun getClient(", 1)[-1]
check("config !== PreferenceManager.connectionSnapshot()" in session and
      session.find("connectionSnapshot()") < session.find("chain.proceed"),
      "Session clients must reject stale connection snapshots before network")
for name, source in (("image", images), ("session transport", raw_client)):
    check(".followRedirects(false)" in source and ".followSslRedirects(false)" in source,
          f"{name} client must disable all redirects")
    check("ServerPolicy.requireAllowed" in source and
          source.find("ServerPolicy.requireAllowed") < source.find("chain.proceed"),
          f"{name} origin validation must precede chain.proceed")
    check("ServerPolicy.isValidApiKey" in source, f"{name} must validate credentials")
check("val config = PreferenceManager.connectionSnapshot()" in images,
      "Images must read one current immutable connection per request")
media = without_comments(text(KOTLIN / "api/PrivateMediaDataSource.kt"))
check("OkHttpDataSource.Factory" in media and "ApiClientFactory.forSession(ApiClientConfig.fromPrefs())" in media,
      "Media requests must use snapshot-bound guarded OkHttp factory")
glide = without_comments(text(KOTLIN / "shared/CustomGlideModule.kt"))
check("ApiClientFactory.forImages()" in glide, "Glide must use dynamic snapshot-guarded image client")
prefs = without_comments(text(KOTLIN / "shared/prefs/PreferenceManager.kt"))
check("@Volatile private var activeConnection" in prefs and
      "fun connectionSnapshot(): ApiClientConfig = activeConnection" in prefs,
      "Connection host and key must be published as one volatile immutable snapshot")
api = without_comments(text(KOTLIN / "api/ApiClient.kt"))
check("fun fromPrefs(): ApiClientConfig = PreferenceManager.connectionSnapshot()" in api and
      "ApiClientFactory.forApiSession(config)" in api,
      "API clients must consume snapshot-bound guarded transport")
for path in KOTLIN.rglob("*.kt"):
    if path.name in ("ApiClient.kt", "PreferenceManager.kt", "ApiClientFactory.kt"):
        continue
    source = without_comments(path.read_text(encoding="utf-8"))
    check("ApiClientFactory.getClient(" not in source,
          f"{path.relative_to(ROOT)}: production client bypasses session/image snapshot wrapper")
    check(path.name == "ProfileStore.kt" or not re.search(r"\bApiClientConfig\s*\(", source),
          f"{path.relative_to(ROOT)}: manually assembled connection may mix host and key")

# User-approved manual updates are the sole public transport exception. Media guards above remain unchanged.
updater = without_comments(text(KOTLIN / "update/UpdateRepository.kt"))
for required in (".cookieJar(CookieJar.NO_COOKIES)", ".authenticator(Authenticator.NONE)",
                 ".proxyAuthenticator(Authenticator.NONE)", ".proxy(Proxy.NO_PROXY)", ".cache(null)",
                 ".followRedirects(false)", ".followSslRedirects(false)", ".callTimeout(",
                 'url.scheme != "https"', 'url.port != 443', 'url.host != "github.com"',
                 'to.host != "release-assets.githubusercontent.com"', 'MAX_REDIRECTS = 3',
                 'https://api.github.com/repos/z979054461/Immich-Android-TV/releases?per_page=100'):
    check(required in updater, f"Manual updater missing isolated transport guard: {required}")
for path in (KOTLIN / "update").glob("*.kt"):
    source = without_comments(text(path))
    check(not any(token in source for token in ("PreferenceManager", "ApiClientConfig", "ApiClientFactory",
              "Timber", "Log.", "WorkManager", "AlarmManager", "BroadcastReceiver")),
          f"{path.name}: updater must never use account/session data, logging, or scheduled/background triggers")
    if path.name != "UpdateRepository.kt":
        check("OkHttpClient" not in source, f"{path.name}: updater must use only its isolated transport")
update_dialog = without_comments(text(KOTLIN / "update/ManualUpdateDialog.kt"))
check("repository.cancel()" in update_dialog and "pending?.cancel(true)" in update_dialog and
      "if (!closed && dialog.isShowing)" in update_dialog and "UpdateInstaller::discard" in update_dialog,
      "Manual update dismissal must cancel work and discard stale installation callbacks")
settings = without_comments(text(KOTLIN / "settings/SettingsFragment.kt"))
check("updateDialog?.dismiss()" in settings.split("override fun onStop()", 1)[-1],
      "Settings must dismiss updater on background without changing profile policy")
verifier = without_comments(text(KOTLIN / "update/UpdateApkVerifier.kt"))
for required in ("ApkVerifier.Builder", "result.isVerified", "result.isVerifiedUsingV2Scheme",
                 "signerDigests != listOf(SIGNER_SHA256)", "SIGNER_SHA256", "verifyDigest(file, candidate)",
                 "packageName != PACKAGE", "versionCode <= installedCode"):
    check(required in verifier, f"Update APK validation missing: {required}")
proguard = text(ROOT / "app/proguard-rules.pro")
check("-keep class com.android.apksig.** { *; }" in proguard and
      "RuntimeVisibleAnnotations" in proguard and "AnnotationDefault" in proguard and "Signature" in proguard,
      "apksig reflective ASN.1 models and annotations must survive release shrinking")
installer = without_comments(text(KOTLIN / "update/UpdateInstaller.kt"))
check("FLAG_GRANT_READ_URI_PERMISSION" in installer and "FLAG_GRANT_WRITE_URI_PERMISSION" not in installer and
      "file.setReadOnly()" in installer and "UpdateApkVerifier.verify(context, prepared.file, candidate)" in installer and
      "MATCH_SYSTEM_ONLY" in installer,
      "Installer must share only a reverified immutable APK read-only with the system")
paths = ET.fromstring(text(APP / "res/xml/update_file_paths.xml"))
check(len(paths) == 1 and paths[0].tag == "cache-path" and paths[0].get("path") == "update_installs/",
      "Update provider must expose only its installation staging subdirectory")

# Mandatory profile lock invariants, in addition to runtime tests.
activity = without_comments(text(KOTLIN / "MainActivity.kt"))
check("FLAG_SECURE" in activity and "super.onCreate(null)" in activity,
      "Activity must not restore authenticated pixels/back stack or expose recent-task screenshots")
def kotlin_function_body(source, name):
    match = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", source)
    if not match:
        return ""
    start = source.find("{", match.end())
    if start < 0:
        return ""
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    return ""

stop = kotlin_function_body(activity, "onStop")
check("PreferenceManager.suspendForBackground()" in stop and "visibility = View.INVISIBLE" in stop and
      stop.find("visibility = View.INVISIBLE") < stop.find("PreferenceManager.suspendForBackground()"),
      "Backgrounding must hide private pixels before suspending transport")
check("configuration = null" in stop and "BackgroundManager.getInstance(this).drawable = null" in stop,
      "Backgrounding must clear media playback configuration and photo backgrounds")
invalidator = kotlin_function_body(prefs, "invalidateConnection")
check('activeConnection = ApiClientConfig("", "", false, false)' in invalidator and
      "activeProfileId = null" in invalidator and "ApiClientFactory.cancelAll()" in invalidator and
      "AssetDetailCache.clearSession()" in invalidator,
      "Suspension must revoke the active credential snapshot, requests and account metadata")
suspension = kotlin_function_body(prefs, "suspendForBackground")
check("profiles.sessionPolicy(id)" in suspension and "policy == current" in suspension and
      "finally" in suspension and "invalidateConnection()" in suspension and
      suspension.find("finally") < suspension.find("invalidateConnection()"),
      "Background grace requires unchanged account policy and unconditional transport revocation")
hard_lock = kotlin_function_body(prefs, "lock")
check("pinGrace.clear()" in hard_lock and "invalidateConnection()" in hard_lock,
      "Hard lock must destroy grace and revoke transport")
resume = kotlin_function_body(prefs, "resumeFromBackground")
check("profiles.sessionPolicy(id)" in resume and "current != pendingProfilePolicy" in resume and
      "pinGrace.consume(id)" in resume and "activeConnection = suspended.copy()" in resume,
      "Grace resume must revalidate the same profile policy, consume the grant and publish a fresh identity")
check("SystemClock::elapsedRealtime" in prefs and "graceHandler.postDelayed" in prefs and
      "graceHandler.removeCallbacks" in prefs,
      "Grace expiry must use monotonic time and a cancellable cleanup timer")
check("Intent.ACTION_SCREEN_ON" in activity and "PreferenceManager.expireBackgroundGrace()" in activity and
      "ContextCompat.RECEIVER_NOT_EXPORTED" in activity and "unregisterReceiver(screenOnReceiver)" in activity,
      "Wake cleanup must discard elapsed grace without exporting a receiver or retaining a dead Activity")
grace = without_comments(text(KOTLIN / "security/SessionGraceController.kt"))
check("now < grant.lastObservedAt" in grace and "now - grant.startedAt >= grant.durationMillis" in grace and
      "pending = null" in grace and "pending?.token !== token" in grace,
      "Grace must reject rollback/exact expiry, drop credentials and reject stale expiry callbacks")
check(not any(token in grace for token in ("SharedPreferences", "SavedStateHandle", "java.io", "android.os.Bundle")),
      "Grace grants must stay memory-only, never in preferences, saved state or files")
launcher_policy = without_comments(text(KOTLIN / "security/LauncherEntryPolicy.kt"))
check("LauncherEntryPolicy.permitsGrace(intent)" in kotlin_function_body(activity, "onNewIntent") and
      "Intent.ACTION_MAIN" in launcher_policy and "Intent.CATEGORY_LAUNCHER" in launcher_policy and
      "Intent.CATEGORY_LEANBACK_LAUNCHER" in launcher_policy and "intent.data != null" in launcher_policy,
      "Only plain launcher returns may consume an existing grace grant")
check("PreferenceManager.lock()" in kotlin_function_body(activity, "onCreate") and
      "PreferenceManager.lock()" in kotlin_function_body(activity, "onDestroy"),
      "Activity reconstruction must conservatively clear all grace authorization")
profiles = without_comments(text(KOTLIN / "security/ProfileStore.kt"))
for required in ("ApiKeyCipher.encrypt", "PinHasher.verify", "ServerPolicy.normalizeServer", "ServerPolicy.isValidApiKey"):
    check(required in profiles, f"ProfileStore missing guard: {required}")
check("DiskCacheAdapter()" in glide and "MemoryCacheAdapter()" in glide,
      "URL-only Glide caches must not bypass per-profile authorization")
dream = without_comments(text(KOTLIN / "screensaver/ScreenSaverService.kt"))
check("PreferenceManager.lock()" in dream and "finish()" in dream and "ScreenSaverAssetLoader(this)" not in dream,
      "Background screensaver must remain disabled while profile PIN is mandatory")
check("AssetDetailCache.clearSession()" in prefs,
      "Lock must purge account-scoped metadata cache")

timeline = without_comments(text(KOTLIN / "timeline/TimelineFragment.kt"))
check("getBackStackEntry(R.id.nav_graph)" in timeline,
      "Timeline model must be cleared with the profile navigation graph")

manifest_path = APP / "AndroidManifest.xml"
manifest = ET.fromstring(text(manifest_path))
a = "{http://schemas.android.com/apk/res/android}"
application = manifest.find("application")
# The isolated Robolectric manifest must model the same signature-only receiver grant as AndroidX.
test_manifest = ET.fromstring(text(ROOT / "app/src/test/AndroidManifest.xml"))
receiver_permission = "io.github.z979054461.immichtv.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
test_receiver_declarations = [node for node in test_manifest.findall("permission")
                              if node.get(a + "name") == receiver_permission]
check(len(test_receiver_declarations) == 1 and
      test_receiver_declarations[0].get(a + "protectionLevel") == "signature",
      "Test manifest must declare exactly the app-owned signature receiver permission")
check(any(node.get(a + "name") == receiver_permission for node in test_manifest.findall("uses-permission")),
      "Test manifest must request its signature receiver permission")
check(application is not None, "Manifest application missing")
if application is not None:
    providers = [node for node in application.findall("provider") if node.get(a + "authorities") == "${applicationId}.updates"]
    check(len(providers) == 1 and providers[0].get(a + "exported") == "false" and
          providers[0].get(a + "grantUriPermissions") == "true", "Update provider must be nonexported with scoped grants")
    check(application.get(a + "allowBackup") == "false", "Manifest allowBackup must be false")
    check(application.get(a + "fullBackupContent") == "false", "Manifest fullBackupContent must be false")
    rules = application.get(a + "dataExtractionRules", "")
    check(rules.startswith("@xml/"), "Explicit dataExtractionRules required for device-transfer exclusions")
    if rules.startswith("@xml/"):
        rules_path = APP / "res/xml" / (rules.split("/", 1)[1] + ".xml")
        rule_root = ET.fromstring(text(rules_path))
        for mode in ("cloud-backup", "device-transfer"):
            exclusions = {(node.get("domain"), node.get("path")) for node in rule_root.findall(f"{mode}/exclude")}
            for domain in ("root", "file", "database", "sharedpref", "external"):
                check((domain, ".") in exclusions, f"{mode}: missing full exclusion for {domain}")
for node in manifest.iter():
    name = node.get(a + "name", "")
    check(not any(word in name for word in ("BILLING", "WRITE_EPG_DATA", "HomeScreenChannelReceiver", "firebase", "cast.tv")),
          f"Manifest contains prohibited component/permission: {name}")

build = without_comments(text(ROOT / "app/build.gradle"))
for banned_dependency in ("firebase", "google.services", "billing", "play.services.auth", "custom.qr.generator"):
    check(banned_dependency not in build.lower(), f"Runtime build still references {banned_dependency}")
for path in (APP / "res").rglob("*.xml"):
    source = path.read_text(encoding="utf-8")
    check("immich.giejay.nl" not in source and "authentication_url" not in source,
          f"{path.relative_to(ROOT)}: author relay resource remains")

# Obsolete upstream promotional/service pointers must not return to this fork.
for path in (ROOT / "README.md", ROOT / "mediaslider/README.md"):
    source = text(path)
    check(not re.search(r"buymeacoffee\.com|github\.com/giejay/Immich-Android-TV/(?:issues|pulls)|Immich-Android-TV-Authentication", source, re.I),
          f"{path.name}: obsolete upstream promotion or service link remains")
funding = ROOT / ".github/FUNDING.yml"
check(not funding.exists() or "giejay" not in funding.read_text(encoding="utf-8").lower(),
      "GitHub funding must not advertise the upstream author's donation account")
for path in (APP / "res").glob("values*/strings.xml"):
    resources = ET.fromstring(text(path))
    check(not any(node.get("name", "") == "donate" or node.get("name", "").startswith("donation_")
                  for node in resources.findall("string")),
          f"{path.relative_to(ROOT)}: obsolete donation strings remain")

# The profile lock permits only an unlocked foreground screensaver preview.
screensaver_preferences = without_comments(text(KOTLIN / "shared/prefs/Preferences.kt"))
check("R.string.screensaver_preview_help" in screensaver_preferences,
      "Screensaver settings must explain the foreground-only preview")
for obsolete in ("DREAM_SETTINGS", "DaydreamActivity", "screensaver_adb", "startScreenSaverIntent"):
    check(obsolete not in screensaver_preferences,
          f"Screensaver preferences still expose background setup: {obsolete}")
check(not (APP / "res/layout/screensaver_adb.xml").exists(),
      "Obsolete ADB screensaver instructions must not be bundled")
check(not (ROOT / ".gitmodules").exists(),
      "Vendored modules must not retain stale submodule configuration")
check(not (ROOT / "screenshots").exists() and not (ROOT / "README.upstream.md").exists(),
      "Obsolete upstream screenshot archive must not return")
for path in ROOT.rglob("*.md"):
    if ".git" not in path.parts:
        source = path.read_text(encoding="utf-8")
        check(not re.search(r"\]\([^)]*(?:README\.upstream\.md|screenshots/)", source),
              f"{path.relative_to(ROOT)}: document links to removed upstream archive")
for path in (ROOT / "mediaslider").rglob("*"):
    if path.suffix in (".md", ".java", ".kt"):
        source = path.read_text(encoding="utf-8")
        check("res.cloudinary.com/" not in source and "kartiksaraf9@gmail.com" not in source,
              f"{path.relative_to(ROOT)}: obsolete personal contact/demo service remains")

# Thumbnail caching is a separate ephemeral encrypted store, never a Glide URL cache.
thumbnail_core = without_comments(text(KOTLIN / "security/PrivateThumbnailCache.java"))
thumbnail_bridge = without_comments(text(KOTLIN / "shared/PrivateThumbnails.kt"))
thumbnail_request = without_comments(text(KOTLIN / "api/PrivateThumbnailRequest.kt"))
thumbnail_transport = without_comments(text(KOTLIN / "api/ThumbnailNetworkSession.kt"))
thumbnail_loader = without_comments(text(KOTLIN / "shared/PrivateThumbnailModelLoader.kt"))
for required in ('"AES/GCM/NoPadding"', "cipher.updateAAD(session.identity)",
                 "cipher.updateAAD(key.authenticatedIdentity())", "Arrays.fill(retired.key", "retired.key = null",
                 "usedConnections", "requireCurrentLocked(session)", "reservedBytes()", "readBounded(",
                 "storageGeneration++", "reservation.generation != storageGeneration"):
    check(required in thumbnail_core, f"Private thumbnail cache missing invariant: {required}")
check("context.cacheDir" in thumbnail_bridge and '"private_thumbnails"' in thumbnail_core,
      "Encrypted thumbnails must stay under the app-private cache directory")
check("ApiClientFactory.forSession(config)" in thumbnail_transport and
      "ServerPolicy.requireAllowed(server, url)" in thumbnail_request,
      "Thumbnail source must retain captured credentials and existing transport guards")
check("ApiClientFactory.forSession(config)" not in thumbnail_request and
      "client = null" in thumbnail_transport and "connection = null" in thumbnail_transport and
      "pool.evictAll()" in thumbnail_transport and "network?.revoke()" in thumbnail_bridge,
      "Thumbnail misses must share a guarded session pool and release it on revocation")
check("cache?.reject(it)" in thumbnail_bridge and "loaded.entryToken" in thumbnail_loader and
      "entry.generation != storageGeneration" in thumbnail_core and "entry.iv" in thumbnail_core,
      "Decoder failure must evict only its captured account/generation/nonce entry")
rejection = thumbnail_core.split("public boolean reject(", 1)[-1].split("private void cleanRejectedEntries(", 1)[0]
check("synchronized (disk)" not in rejection and "synchronized (state)" not in rejection and
      "MAX_REJECTED_ENTRIES" in rejection and "cleanup.execute(this::cleanRejectedEntries)" in rejection,
      "Decoder rejection must be bounded memory work with background-only disk cleanup")
check('segments[2] != "thumbnail"' in thumbnail_request and '"assets" ->' in thumbnail_request and
      '"people" ->' in thumbnail_request, "Thumbnail caching needs an explicit endpoint whitelist")
check("PrivateThumbnails.revoke()" in invalidator and "PrivateThumbnails.begin(activeConnection, id)" in resume,
      "Background invalidation must revoke cached media even during PIN grace")
check("activeConnection = connection.copy()" in prefs,
      "Profile activation must not republish a previously captured connection identity")
check("deliverIfCurrent" in thumbnail_bridge and "callback.onDataReady(loaded)" in thumbnail_loader and
      "loaded.requireCurrent()" in thumbnail_loader,
      "Thumbnail streams and decoded target delivery must both remain session-guarded")
thumbnail_settings = without_comments(text(KOTLIN / "settings/ThumbnailCacheSettingsDialog.kt"))
check("setNeutralButton(R.string.thumbnail_cache_clear" in thumbnail_settings and
      "clearThumbnailCache(profileId, connection)" in thumbnail_settings and
      "activeConnection === expectedConnection" in prefs,
      "Explicit cache clear must preserve settings and reject stale account/session dialogs")
check("MemoryCacheAdapter()" in glide and "DiskCacheAdapter()" in glide and "BitmapPoolAdapter()" in glide,
      "Private cache must not reactivate Glide's shared plaintext/URL caches")
for relative in ("card/CardPresenter.kt", "timeline/MemoryPresenter.kt", "timeline/TimelineMosaicAdapter.kt"):
    presenter = without_comments(text(KOTLIN / relative))
    check("PrivateThumbnails.model(" in presenter and "PrivateThumbnails.guard()" in presenter,
          f"{relative}: cached thumbnails require captured model and final delivery guard")

if failures:
    print(f"FAIL: {len(failures)} privacy checks failed ({checks} checks)")
    for failure in failures:
        print(f" - {failure}")
    sys.exit(1)
print(f"PASS: {checks} private runtime source/configuration checks")
print("Not a claim of device testing, binary inspection, or server compatibility.")
