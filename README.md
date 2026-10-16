# Meme Radar (Android)
Native port of meme_radar.py: scans Solana/Base/BSC/ETH 24/7 in a foreground service and sends a
notification when a coin passes gates + score + 2-cycle confirmation + RugCheck/GoPlus safety checks.

## Get the APK (no Android Studio needed)
1. Create a GitHub repo, push this folder's contents.
2. Actions tab → "Build APK" → download `MemeRadar-debug-apk` → unzip → install `app-debug.apk`
   (allow "install unknown apps").
Or open in Android Studio and press Run.

## First run
Allow notifications, tap "Allow background", then "Start 24/7 scanning".
Xiaomi/Samsung/Oppo/Huawei: also enable Autostart / set app to "Unrestricted" and lock it in Recents.
It restarts after reboot automatically.
