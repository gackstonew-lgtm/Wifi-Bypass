# WiFiBridgeBypass

An Android Studio project (Kotlin) that runs a local TCP forwarding proxy
so a second device on the same Wi-Fi network can route traffic through
this phone's active connection.

## Setup
1. Open this folder in Android Studio (File > Open).
2. Let Gradle sync (it will download the AGP/Kotlin/AndroidX/Ktor dependencies).
3. Run on a device/emulator with Android 9.0 (API 28) or newer.

## Notes
- `ProxyBridgeService.kt` currently accepts connections on port 8080 but the
  `handleClient()` method is a skeleton — it logs the connecting client and
  closes the socket immediately. You'll need to implement the actual
  HTTP request parsing and byte-piping to a target socket for this to
  forward real traffic (see the comments in that file for the outline).
- This app only opens the network's own login/sign-in page for the user to
  authenticate manually — it does not attempt to bypass any authentication
  or payment step on a captive portal.
- No custom app icon assets (PNG mipmaps) are included; Android Studio will
  prompt you to generate one via Image Asset Studio, or you can replace the
  adaptive-icon XML in `res/mipmap-anydpi-v26/` with your own.
