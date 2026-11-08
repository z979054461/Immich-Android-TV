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
            check(path == KOTLIN / "api/ApiClientFactory.kt",
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

# Mandatory profile lock invariants, in addition to runtime tests.
activity = without_comments(text(KOTLIN / "MainActivity.kt"))
check("FLAG_SECURE" in activity and "super.onCreate(null)" in activity,
      "Activity must not restore authenticated pixels/back stack or expose recent-task screenshots")
check("override fun onStop()" in activity and "PreferenceManager.lock()" in activity,
      "Activity must lock on backgrounding")
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
check(application is not None, "Manifest application missing")
if application is not None:
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

if failures:
    print(f"FAIL: {len(failures)} privacy checks failed ({checks} checks)")
    for failure in failures:
        print(f" - {failure}")
    sys.exit(1)
print(f"PASS: {checks} private runtime source/configuration checks")
print("Not a claim of device testing, binary inspection, or server compatibility.")
