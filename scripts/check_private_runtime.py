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
                 "screensaver/ScreenSaverService.kt", "screensaver/ScreenSaverPreviewFragment.kt"):
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
check("config != PreferenceManager.connectionSnapshot()" in session and
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
      "ApiClientFactory.forSession(config)" in api,
      "API clients must consume snapshot-bound guarded transport")
for path in KOTLIN.rglob("*.kt"):
    if path.name in ("ApiClient.kt", "PreferenceManager.kt", "ApiClientFactory.kt"):
        continue
    source = without_comments(path.read_text(encoding="utf-8"))
    check("ApiClientFactory.getClient(" not in source,
          f"{path.relative_to(ROOT)}: production client bypasses session/image snapshot wrapper")
    check(not re.search(r"\bApiClientConfig\s*\(", source),
          f"{path.relative_to(ROOT)}: manually assembled connection may mix host and key")

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

if failures:
    print(f"FAIL: {len(failures)} privacy checks failed ({checks} checks)")
    for failure in failures:
        print(f" - {failure}")
    sys.exit(1)
print(f"PASS: {checks} private runtime source/configuration checks")
print("Not a claim of device testing, binary inspection, or server compatibility.")
