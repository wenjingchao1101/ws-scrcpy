# Android clipboard compatibility patch

The bundled WebSocket server is based on scrcpy `3.3.4` and locally patched as
`3.3.4-ws8.2`. The WebSocket transport and server arguments are retained from
the fork, while the browser control messages are encoded for scrcpy 3.3.4.
Only `ClipboardManager` and executable version strings are replaced; the
remaining classes from the original binary are retained. The Node server's
`SERVER_VERSION` must match this version so it replaces older WebSocket server
processes.

The clipboard wrapper probes exact Binder signatures for package-only, user ID,
attribution tag, and device ID variants. It adapts reads, writes, listener registration
and listener removal. It uses `com.android.shell`, user 0 and default device 0,
retaining the original server's user policy. An invocation/permission failure is
reported rather than retried as another API variant.

API reference: [Android 16 IClipboard.aidl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/content/IClipboard.aidl).
The original server derives from the WebSocket build published in
[Mel0nFish/ws-scrcpy-enhanced](https://github.com/Mel0nFish/ws-scrcpy-enhanced),
commit `32e58f9`; the upstream official scrcpy 5.x server does not include the
WebSocket mode required by this project.
The parent directory's Apache-2.0 LICENSE applies to these patch sources as well.

## Rebuild

Normal users only need to run the usual `npm run dist:prod` and restart the Node
service; the patched `scrcpy-server.jar` is already included. Close and reopen the
device stream after restarting so the old device process is replaced.

To rebuild the JAR patch itself, use Python 3, a JDK, Android API 30 `android.jar`,
and these build libraries in a tools directory (filenames in parentheses):

- Google Maven `com.android.tools:r8:8.3.37` (`r8.jar`)
- Maven Central `org.smali:dexlib2:2.5.2` (`dexlib2.jar`)
- Maven Central `com.google.guava:guava:27.1-android` (`guava.jar`)
- Maven Central `com.google.code.findbugs:jsr305:3.0.2` (`jsr305.jar`)

Use the original **unpatched** `1.19-ws8` JAR from Git history as input, not the
current patched JAR. The build rejects an already-patched input.

```bash
python test_patch.py /path/to/jdk
python build_patch.py --java-home /path/to/jdk --tools /path/to/tools \
  --android-jar /path/to/android.jar --input /path/to/original-server.jar \
  --old-version 3.3.4 --new-version 3.3.4-ws8.2 \
  --output ../scrcpy-server.jar
```

The JVM checks exercise all four signatures, Chinese/emoji text, empty clipboard,
listener add/remove, missing APIs and permission failures. They do not replace a
device test. On Android 16, verify desktop-to-phone Chinese paste and phone-to-desktop
copy with text selected; browser clipboard write permission is also required.
