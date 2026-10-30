// The sync task: answers the phone's commands (docs/esp32-protocol.md sections 3–6 and 10),
// one at a time, using the recordings in the store.
#pragma once

void sync_start(void);

// Something in the status changed (a recording was saved): the scan response gets the new
// pending count within a second.
void sync_status_changed(void);

// The writer saved a recording: the same, and stay awake advertising it for 2 min so the
// phone can sync it.
void sync_recording_saved(void);

// A tap: set the CHECK_IN flag in the advertised status (protocol section 10), so the phone
// connects and reads a fresh status, until it does.
void sync_ask_for_check_in(void);
