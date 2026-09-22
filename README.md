# WiFiBridgeBypass

An Android Studio project (Kotlin) that runs a local **SOCKS5 proxy** so a
second device — connected to this phone's manually-enabled Mobile Hotspot
or USB tether — can relay its traffic through this phone's already
validated Wi-Fi connection. This is the same thing native Wi-Fi tethering
does; it's implemented at the app layer for devices where the OS doesn't
support Wi-Fi-to-Wi-Fi tethering natively (e.g. some Samsung Note 8 builds).

## Setup
1. Open this folder in Android Studio (File > Open).
2. Let Gradle sync (downloads AGP/Kotlin/AndroidX/Coroutines).
3. Run on a device with Android 9.0 (API 28) or newer.

## How it works
- `WifiUtils.kt` classifies the active network as `Disconnected`,
  `CaptivePortal`, `NoInternet`, or `Ready(network)`, and exposes a Flow
  that emits whenever that state changes.
- `ProxyBridgeService.kt` runs a foreground service that:
  1. Watches that Flow and only opens the SOCKS5 listener (port 1080) once
     Wi-Fi is `Ready` — i.e. connected, validated, and not behind a portal.
  2. Accepts SOCKS5 `CONNECT` requests from clients on the local subnet
     only (loopback / 10.x / 172.16–31.x / 192.168.x — anything else is
     rejected).
  3. For each request, opens a fresh outbound socket and calls
     `Network.bindSocket()` on it so it is physically pinned to the Wi-Fi
     interface — it cannot silently fall back to cellular.
  4. Pipes bytes bidirectionally between the client and target sockets
     until either side closes.
  5. If Wi-Fi drops, loses validation, or a captive portal reappears, the
     service immediately closes the listener and every open connection.
- `MainActivity` / `MainViewModel` show connection status, a button that
  deep-links into system Hotspot settings (apps can't toggle the hotspot
  programmatically on Android 10+), a Start/Stop button for the bridge,
  and the local IP address(es) to enter as the SOCKS5 proxy on the second
  device.

## Testing checklist
- [ ] Connect the phone to Wi-Fi with no captive portal — bridge should
      become available to start.
- [ ] Connect to a network with a captive portal — the app should show
      "sign-in required" and refuse to start the listener; complete the
      sign-in, return to the app, and confirm it picks up automatically.
- [ ] Turn on Mobile Hotspot, connect a laptop, set its SOCKS5 proxy to
      the phone's hotspot IP on port 1080, and load a test page.
- [ ] Turn off Wi-Fi while a page is loading through the bridge — confirm
      the transfer is cut off and the app shows the bridge stopped rather
      than silently continuing over cellular.
- [ ] Try connecting to port 1080 from a device *not* on the phone's local
      subnet (e.g. over the internet) — should be refused.

## Notes / limitations
- This only relays traffic between devices already on your own local
  network (hotspot/USB tether) through your own already-authenticated
  Wi-Fi session. It intentionally does **not** implement anything to
  disguise multiple devices as one to a captive portal, spoof MAC/device
  identifiers, or otherwise defeat per-device authentication or billing a
  network operator enforces — many hotel/airport/paid Wi-Fi networks use
  "one device per login" specifically as their access-control mechanism,
  and circumventing that may violate that network's terms of service.
  Use this only on networks and in ways you're actually permitted to.
- The client device needs a SOCKS5-capable proxy client (most browsers
  and OSes support SOCKS5 proxy settings natively).
- No custom app icon assets (PNG mipmaps) are included; Android Studio will
  prompt you to generate one via Image Asset Studio, or replace the
  adaptive-icon XML in `res/mipmap-anydpi-v26/` with your own.
