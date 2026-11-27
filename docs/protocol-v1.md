# ezcall Bluetooth protocol v1

Android advertises a connectable BLE GATT service. The receiving phone (iPhone or Android) is the central and enables TX indications before writing to RX. These UUIDs are public and carry no personal data.

| Attribute | UUID | Operation |
| --- | --- | --- |
| ezcall | `1C79EA10-64D7-4F93-94EE-881ACB0D5E74` | Discover |
| RX | `1C79EA11-64D7-4F93-94EE-881ACB0D5E74` | Write with response |
| TX | `1C79EA12-64D7-4F93-94EE-881ACB0D5E74` | Indicate with standard CCCD |
| Audio RX | `1C79EA13-64D7-4F93-94EE-881ACB0D5E74` | Write without response |
| Audio TX | `1C79EA14-64D7-4F93-94EE-881ACB0D5E74` | Notify with standard CCCD |

Each direction is a byte stream. A frame starts with a four-byte big-endian length in `1...65,536`, followed by that many body bytes. ATT fragments arrive in order and may split any field. The server starts with 20 data bytes per indication and can increase to the lesser of 180 and `MTU - 3`. The central limits each write to the lesser of 180 and the current maximum ordinary ATT payload. Wait for each operation's completion before sending the next fragment. Discard partial frames and all session state on disconnect.

## Pairing and authentication

1. An explicit request on Android opens a short pairing window and shows `xcall:v1:<secret>`. The secret is 32 random bytes encoded in unpadded Base64url. The QR is a credential and is displayed only during enrollment. Both sides store it in OS-protected storage after authentication. A reset generates a new credential.
2. Each side generates a fresh ephemeral P-256 key pair. Receiver sends `01 || c`, where `c` is its 65-byte uncompressed SEC1 public key. Android responds with `02 || s || mac`, where `s` is its public key in the same encoding and `mac = HMAC-SHA256(secret, ASCII("xcall/v1/android") || c || s)`. The receiver verifies the proof with a constant-time check. Reject invalid public keys.
3. Each side computes the 32-byte raw ECDH shared secret and two 32-byte HKDF-SHA256 outputs. The HKDF input key is the shared secret and the salt is `HMAC-SHA256(secret, ASCII("xcall/v1/kdf") || c || s)`. The info labels are `xcall/v1/c2a` (Receiver → Android) and `xcall/v1/a2c` (Android → Receiver).
4. Each encrypted body is `03 || counter || ciphertext || tag`. The unsigned counter is eight-byte big-endian and starts at zero independently for each direction on each connection. The AES-256-GCM nonce is four zero bytes followed by the counter. The first nine body bytes form the associated data and the tag is 16 bytes. Reject an unexpected counter, malformed frame, or failed authentication and terminate the session.
5. The first encrypted JSON object from the receiver is `{"type":"ready"}`. Android proves the reverse direction with its own `{"type":"ready"}`. Sensitive data can only flow after both sides authenticate and Android has granted the corresponding sharing permission.
6. Key rotation keeps a copied QR from working after enrollment. Both sides derive `next` with the same HKDF input and salt and the info label `xcall/v1/next`. A receiver that has stored `next` adds `"rekey":"1"` to its `ready`. Android then saves `next` in place of its secret and uses it for the next handshake. The receiver keeps the key that verified this handshake along with `next`, and accepts Android's proof under either key. Whichever key verifies becomes current, so a lost `ready` never locks the receiver out. Peers without `rekey` keep the QR secret.

## Events

Messages are UTF-8 JSON objects. Times use Unix epoch milliseconds. Request IDs are opaque strings echoed in the response.

| Sender | Type | Fields |
| --- | --- | --- |
| Receiver | `ping` | `id` |
| Android | `pong` | `id` |
| Receiver | `sync` | `id`, optional boolean `watch` to request change notifications for shared data on this link |
| Android | `sync_changed` | Shared contacts or SMS changed. A subscribed receiver can request an update. |
| Android | `sync_begin` | `id`, optional two-letter SIM `country` for matching national and international phone numbers |
| Android | `sms_batch` | `id`, `items` array of SMS objects |
| Android | `contacts_batch` | `id`, `items` array of contact objects |
| Android | `sync_end` | `id`, optional incremental sync fields described below |
| Android | `error` | `id`, `message` |
| Android | `sms` | SMS fields |
| Android | `call` | `id`, `address`, `date`, `state` |

| Android | `capabilities` | `control`, `dial` (booleans), `audio` (`none`, `listen` or `two_way`). Optional booleans `texts`, `contacts`, and `send_sms` report Android grants. Optional `incremental_sync` advertises cursor-based sync. Optional `active_call_id` is the current relayed call ID, or an empty string when there is none. |
| Receiver | `call_answer` | `id` of a ringing call |
| Receiver | `call_end` | `id` of a ringing, active or requested call |
| Receiver | `call_dial` | `id` (new UUID), `address` |
| Receiver | `call_dtmf` | Active call `id`, `digit` (one of `0`–`9`, `*`, `#`). Send only if `audio_start` advertised `dtmf: true`. |
| Receiver | `call_resume` | `id` of the interrupted active call, `audio` (`"1"` if the audio session is retained) |
| Android | `call_resumed` | `id`, `audio` (whether the retained audio stream resumed) |
| Receiver | `audio_ready`, `audio_paused` | `id` of the active SIM call, when supported by `audio_route` in capabilities |
| Receiver | `test_audio_heard` | `id` of the active simulated call, when that call has `test_audio_confirmation: true` |
| Receiver | `test_audio_status` | `id` of the active simulated call, when that call has `test_audio_diagnostics: true`. `step` is a known receiver checkpoint. Automatic evidence uses `CUE_PLAYED`, `MIC_SIGNAL`, and `ECHO_PLAYED`. |
| Android | `call_error` | `id`, `message` |
| Android | `audio_start` | `id`, `key` (Base64, 32 bytes), `uplink` (boolean), optional `audio_pairs` and `dtmf` (booleans). Simulated calls can include `test_echo_sequence: 4096`. |
| Android | `audio_stop` | `id`, optional `message` |
| Receiver | `sms_send` | `id` (new UUID), `address`, `body` |
| Android | `sms_result` | `id`, `sent` (boolean), optional `message` |
| Android | `unpair` | `id` (new UUID). Sent only to a peer that advertised `unpair: "1"` in its authenticated `ready`. |
| Receiver | `unpair_ack` | Matching `id`, sent after removing the saved pairing and clearing its synced data. |

An SMS object has string `id`, `address`, `body`, numeric `date` and `direction` (`in` or `out`). A contact object has string `id`, `name` and string-array `numbers`. Call states are `ringing`, `active` and `ended` with one ID per observed call. An empty call address means the number was unavailable.

Android advertises `send_sms` only when the owner opts in and Android grants the send permission. The receiver waits for the matching result, which reports Android’s send status. A dropped link can leave the result unknown. Retry requires a new explicit action from the user.

After the Android owner confirms **Forget receiving phone**, Android deletes its saved credential and allows only the acknowledgement for the closing session. If an acknowledgement cannot be obtained, Android shows a fresh QR and the owner can clear the old record on Receiver manually. Test calls include `simulated: true`. The Sender streams test tones and then echoes authenticated audio payloads from Receiver back down the audio channel.

## Calls on the receiving phone

Android sends `capabilities` after each authentication and when call audio starts or stops on Android. Call IDs are lowercase UUID strings and work as CallKit UUIDs on iPhone. For an incoming call, the receiver answers or declines with the ID from `ringing`. For an outgoing call, the receiver sends a new ID with `call_dial`. Android uses it for that call's `active` and `ended` events. The SIM call stays on Android if the link drops.

If Android advertises `resume_call: true` in capabilities, the receiver can retain an active call during a short outage and send `call_resume` after authenticating again. Android confirms only the matching active call within its recovery window. Both sides drop queued audio and keep the live audio key and sequence counters when reattaching that stream. A call that ended during the outage cannot resume.

With `audio_route: true`, a receiver with an active two-way audio engine can signal readiness even while muted. The sender starts relaying PSTN audio after `audio_ready` and leaves the sender phone’s audio mode under Android’s control. The relay pauses or stops on audio pause, Bluetooth loss, a media failure, or the end of the call. If the sender owner chooses to continue locally, Android stops relaying audio for that call and sends `audio_stop`. The cellular call itself can continue.

Audio moves to the receiver only for a call answered or placed from the receiver. When that call is active, Android sends `audio_start` with a fresh key. The audio characteristics then carry one packet per write or notification. Each packet is `05 || sequence || ciphertext || tag`. The sequence is a four-byte big-endian counter that starts at zero in each direction for each `audio_start`. The AES-256-GCM nonce is a four-byte direction (`1` Android → Receiver, `2` Receiver → Android), four zero bytes and the sequence. The first five bytes are the associated data. A receiver accepts any sequence greater than the last one it accepted and drops others. Sequence gaps are allowed because a sender drops stale packets under congestion.

With `dtmf: true`, the Sender synthesizes a tone locally on the active SIM call’s audio uplink. The digit travels on the authenticated control channel, outside the Opus stream. Microphone mute does not affect sender-generated tones. Never replay a digit after a reconnect.

With `test_echo_sequence`, simulated downlink sequences below that value are tones. The Sender skips to the specified sequence for the first returned microphone packet. Older receivers already accept the gap and can still decode the Opus stream. Android Receiver verifies that non-silent PCM from each phase advances through its playback track.

By default each payload carries one 20 ms Opus packet, mono at 48 kHz. An iPhone or Android Receiver that sends `audio_pairs: "1"` in its authenticated `ready` can receive two Opus packets in one notification. Android enables this for a stream with `audio_pairs: true` in `audio_start`. Its downlink payload is a two-byte big-endian length for the first packet, followed by both Opus packets. The uplink and simulated call streams use single Opus packets. The plaintext payload is limited to 1,024 bytes and the sealed packet must fit in one ATT payload. Without `uplink`, the receiver only plays audio. Android pauses bulk sync while audio streams, since an indication blocks the next notification.

Without a cursor, snapshots contain up to 100 recent SMS and 5,000 contacts for compatibility with older receivers.

When Android advertises `incremental_sync`, the receiver can add `smsCursor` to `sync`. Send `"0:0"` to start. Android pages through inbox and sent SMS in ascending order by provider timestamp and row ID, with up to 100 SMS per page. `sync_end` returns the cursor for the next request and sets `moreSMS: true` when another page is ready. A receiver commits the page and its cursor together and merges live events by SMS ID.

The receiver may also send its last `contactsVersion`. Android returns a contacts snapshot and the new version in `sync_end` when the shared contacts change. A new version with no contacts means the current contact list is empty. Omission of the version leaves previously synced contacts intact unless sharing has been revoked. Interrupted pages do not advance the receiver's cursor.

Fixed values for interoperability tests are in [protocol-v1.json](../tests/protocol-v1.json), including audio packets in both directions. Its predictable QR secret and audio key are for tests only.
