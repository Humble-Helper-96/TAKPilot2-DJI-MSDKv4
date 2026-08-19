# Notices

`LICENSE` is plain MIT and covers the TAKPilot2 code in this repository. It is kept
free of any other text so that automated licence detection reads it correctly. The
terms below sit alongside it; they do not modify it.

## What the MIT licence does NOT cover

**The DJI Mobile SDK binary.** This project builds against `com.dji:dji-sdk`,
resolved from Maven. It is not bundled in this repository and it is licensed
separately under DJI's own SDK licence terms (https://developer.dji.com).

**DJI's Sample Code.** This project is built on DJI's Mobile SDK for Android sample
application (https://github.com/dji-sdk/Mobile-SDK-Android), which DJI releases under
the MIT License. That original notice is preserved, as its terms
require, in `LICENSE-DJI-SAMPLE`.

**The vendored RTSP client.** `app/src/main/java/com/pedro/rtsp/` is taken from
rtmp-rtsp-stream-client-java (Copyright pedroSG94), licensed under the Apache License
2.0. Its notice is preserved, as that licence requires, in
`app/src/main/java/com/pedro/rtsp/NOTICE.txt`.

## This software commands an aircraft

It is provided "as is" and without warranty of any kind, as `LICENSE` states. The
operator of the aircraft is responsible for the safety of the flight and for
compliance with the applicable aviation regulations. Each release states in its own
notes whether it has been validated in flight; do not assume that it has.
