# On-device test checklist

The Java and Python tests cover the matcher, subtitle parsing, the film clock, and the
server. They can't cover audio routing, Android lifecycle, gestures, or accessibility. Those
need a real phone. Record the device model, Android version, and headphones for each run.

## Navigation and accessibility

1. The home screen shows **My Films**, **Online Catalogue**, **Manual Player**, and a menu.
   TalkBack reads each control correctly.
2. Repeat the checks below with TalkBack enabled and with large system fonts.
3. In the Manual Player, a single tap on the subtitle display does nothing. A double tap opens
   fullscreen landscape subtitles. A second double tap, or Back, returns to the player.
4. With TalkBack, the subtitle display offers the fullscreen toggle as its click action. Cues
   are not announced unless the announcement setting is on.

## Audio routing

1. AD stays muted on the phone speaker and earpiece.
2. Wired headphones, Bluetooth (A2DP) headphones, and USB headsets each play AD once the route
   is confirmed. Bluetooth and generic USB outputs also need the "this is headphones" checkbox.
3. Disconnecting headphones during playback mutes AD.
4. Rotation and fullscreen subtitles do not interrupt or restart AD.

## Synchronization

1. Lock normally, then cover the microphone for one minute and for several minutes. AD and
   SDH both keep advancing (holdover). There is no timeout.
2. Offset the reference by ±300 ms. The displayed clock never steps, and AD speed stays
   between 0.995× and 1.005×.
3. Speed changes are not audible in speech over Bluetooth headphones. `PlaybackParams` works
   without glitches on the device.
4. Seek the source by 10 seconds. A single detection changes nothing. Two consistent new
   positions cause one jump, with AD and SDH moving together.
5. Pause and resume the source during holdover. The phone stays in holdover and only catches up
   after a corroborated new lock.
6. Compare the `MediaPlayer` position, what is actually heard, and the SDH timing. Don't treat
   player position as a measure of acoustic latency.

## Lifecycle

1. Start synchronization with the screen on, then turn the screen off and put the phone in a
   pocket. AD continues, and the session notification is visible.
2. Stop the session with the main button and with the notification action. Both stop the
   microphone and AD and remove the notification.
3. Check behaviour under the device's battery optimization settings.
4. Known limitation: if Android destroys the Activity or kills the process, the session does
   not resume.

## Catalogue and offline library

1. After setting the server address, the online catalogue loads automatically.
2. A downloaded film appears under **My Films** and plays with networking turned off.
3. Previously downloaded films survive an app update built with the same signing key.
