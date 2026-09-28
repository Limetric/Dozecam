# Night spike (#58)

Throwaway app that answers #58: does Dozecam survive a locked night on iOS,
and what can wake a sleeping parent? It installs as `app.dozecam.dev`
("Night Spike") and writes everything to `Documents/spike.log`.
Findings: https://github.com/Limetric/Dozecam/issues/58#issuecomment-5868712543

```sh
spikes/ios-night/spike.sh build && spikes/ios-night/spike.sh install
spikes/ios-night/spike.sh launch     # not attached to the debugger
spikes/ios-night/spike.sh launch -mixWithOthers NO -armTrigger 30   # override settings
spikes/ios-night/spike.sh log        # pull and print the log
spikes/ios-night/spike.sh kill       # SIGKILL, like iOS terminating it
```

What the app does while **Start** is on:

- an `AVAudioSession` `.playback` session (`.mixWithOthers` by default) with
  an `AVAudioEngine` rendering silence, i.e. Dozecam's "sound off" state;
- a heartbeat (default 30 s) that logs app state, lock state, engine, media
  volume, battery, Low Power Mode, thermal state, memory headroom and a TCP
  probe of the testbed on port 18554, and that restarts the engine if it has
  stopped;
- a dead-man, rescheduled every beat to fire N minutes out, both as a
  time-sensitive notification and (by default) as an AlarmKit alarm;
- a Live Activity ("Monitoring 2 rooms"), updated every beat, stale after
  four missed beats, and restarted from the background if it ends;
- auto-arm on launch if the previous run was monitoring, and a record of how
  the previous run ended plus the notifications delivered while it was gone.

The alert lab fires any mix of AlarmKit (`.fixed(now)`, snooze = 60 s
countdown), a time-sensitive notification with a bundled tone, the app's own
tone through the running engine, and vibration, after a delay so the device
can be locked first. The log's NOTE field records test conditions.

## Protocol

Every run starts from the home screen, never from Xcode: a debugged app is
never suspended.

1. **Permissions**: Request permissions (notifications, alarms), then Start
   (local network).
2. **Alert paths**, one channel at a time, trigger 30 s, device locked. For
   each: normal, silent mode, Sleep Focus, media volume 0. Log a NOTE first.
3. **Dead-man**: dead-man 1, 2, 3 min; `spike.sh kill`; note when the
   notification arrives.
4. **Interruptions**: Music playing (mix off, then on), Siri, a FaceTime call,
   Low Power Mode. Check the log for ENDED and whether the engine came back.
5. **Overnight**: one night on the charger, one off it. Pull the log in the
   morning. Gaps between BEAT lines are suspensions.
6. **iPhone only**: vibration, a face-down phone, the Dynamic Island.
