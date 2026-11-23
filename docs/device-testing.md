# On-device test checklist

The Java and Python tests cover the matcher, subtitle parsing, the film clock, and the
server. They can't cover audio routing, Android lifecycle, gestures, or accessibility. Those
need a real phone. Record the device model, Android version, and headphones for each run.

## Navigation and accessibility

1. The home screen shows **My Films**, **Online Catalogue**, **Manual Player**, and a menu.
   TalkBack reads each control correctly.
2. Repeat the checks below with TalkBack enabled and with large system fonts.
3. In the player, a single tap on the subtitle display does nothing. A double tap opens
   fullscreen landscape subtitles. A second double tap, or Back, returns to the player.
   While fullscreen, the screen doesn't time out. A single tap shows a small bar with subtitle
   timing and text size, which hides again after 4 seconds; the text size is kept as the new
   default. With TalkBack, the bar is opened through the subtitles' actions menu.
4. With TalkBack, the subtitle display offers the fullscreen toggle as its click action. Cues
   are not announced unless the announcement setting is on.

5. The Manual Player's setup page reports each file's check result, warns about a length
   mismatch, and only enables Continue with a fingerprint and at least one track.
6. The timing buttons move AD and subtitles by 0.1 seconds and TalkBack announces the new
   value. A shift set in Settings is still there after restarting the app; a shift made on
   the player is not.

## Audio routing

1. AD stays muted on the phone speaker and earpiece, and the player asks for headphones.
2. Wired headphones, Bluetooth (A2DP) headphones, USB headsets, and hearing aids each play AD
   once Android confirms the route. The player names the device.
3. A Bluetooth speaker also plays AD, because Android reports it like headphones. Check that
   its name is shown clearly enough to notice.
4. Disconnecting headphones during playback mutes AD.
5. Rotation and fullscreen subtitles do not interrupt or restart AD.

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

1. Opening a film starts listening without any button; the first time, Android asks for
   microphone permission. Turn the screen off and put the phone in a pocket. AD continues,
   and the session notification is visible.
2. Leave the player with Back, and stop the session from the notification. Both stop the
   microphone and AD and remove the notification; the notification also closes the player.
3. Switching audio description off silences it at once while subtitles continue. Switching
   it on again resumes at the right place. TalkBack reads the toggle as a switch.
4. Check behaviour under the device's battery optimization settings.
5. Known limitation: if Android destroys the Activity or kills the process, the session does
   not resume.

## Catalogue and offline library

1. After setting the server address, the online catalogue loads automatically.
2. A downloaded film appears under **My Films** and plays with networking turned off.
3. Previously downloaded films survive an app update built with the same signing key.
