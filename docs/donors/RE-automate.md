# Automate (LlamaLab) — teardown, and what Masamune takes from it

> "use the app automate by llama inc — to give masamune an n8n type flow plane
> and menus, shamelessly harvest it's organs." — user, 2026-08-02

Package `com.llamalab.automate`. **Closed source, Play-Store only** — not on
F-Droid, and both APK mirrors answer 403 through this sandbox's proxy, so
`tools/donor-assets/teardown.sh` cannot be pointed at it the way it was at the
fourteen donors in `understory-firewall/docs/donors/teardown/`.

The substitute is better than it sounds. Automate's own block reference
documents **every one of its 418 blocks individually**, with its category, its
internal class name, its ports and its arguments. That is a more precise organ
list than a resource teardown would have produced: an APK gives you the menu
titles, but this gives you the execution model.

Harvested from `llamalab.com/automate/doc/` — `block/index.html`, `flow.html`,
`expression.html`, `value.html`, `variable.html`.

---

## The three organs

Everything below is downstream of these. If Masamune's flow plane takes nothing
else, it takes these.

### 1. There are only two block shapes

| Shape | In | Out |
|---|---|---|
| **Action** | one or more `IN` | exactly one — `OK` |
| **Decision** | one or more `IN` | two — `YES` and `NO` |

That is the whole graph grammar. 418 blocks, two shapes.

This matters more than it looks. n8n's node model carries typed multi-port
inputs, per-port data schemas, and a separate branching node vocabulary — and it
is *baggier for it*. Automate gets conditional control flow for free by making
the condition a property of the block that observed it, rather than a separate
`IF` node fed by an upstream node's output.

**Take the two-shape grammar.** Take n8n's *canvas and palette feel*, which is
what the user is asking for by name — not its node model.

### 2. `Proceed` collapses trigger and condition into one block

Their documentation states the contrast outright:

> Other automation apps differentiate between "event triggers" and
> "condition/constraint checks". Automate combines the same functionality into a
> single block, using different Proceed options.

So `Location at` is one block that either **tests** whether you are at a place
right now, or **waits** until you enter or leave it. `Wi-Fi network connected`
likewise. The block is the subject; `Proceed` is the tense.

This is the single highest-value organ in the app, and it is a *design* organ —
no code needed to steal it. The alternative (Tasker's model, and n8n's) doubles
the palette and forces the user to learn which half of it they are in.

`Proceed` is a **compile-time option**, not a runtime argument — see organ 3.

### 3. Block configuration has exactly three sections

| Section | Evaluated | May contain |
|---|---|---|
| **Options** | compile time — fixed when the user edits the flow, never changes while running | enumerated choices only. `Proceed` lives here |
| **Input arguments** | run time, per fiber | expressions, variables, operators, or a constant. **Almost all are optional** — unspecified means a documented sensible default |
| **Output variables** | run time, per fiber | a bare variable *name*. Never an expression |

The asymmetry is deliberate and worth preserving: inputs are expressions,
outputs are names. A block editor that lets you type an expression into an
output field is one that cannot bind a result.

---

## The fiber model

A **flow** is the graph — the "source code". A **fiber** is a running instance
of it. The distinction is load-bearing:

- One flow may have **many fibers running at once**, each pointing at its own
  current block and each holding **its own independent copy of the user
  variables**.
- A fiber is created by starting a flow manually, by the `Flow start` block, or
  by `Fork`, which **clones an already-running fiber** — including its variable
  values.
- A fiber stops on manual stop, on `Flow stop` / `Fiber stop`, **on reaching an
  unconnected dot**, or on error. A flow counts as stopped only when all of its
  fibers have stopped.
- Scheduling is **cooperative multitasking — not a thread per fiber.** Threads
  are used only for genuinely blocking work (disk, network).
- **Fibers are persisted to internal storage and resume from their last block
  after a device shutdown.**

That last point is the one most likely to be dropped by a reimplementation and
the most painful to retrofit. Persist-and-resume is not a feature bolted onto
the runtime; it dictates that a fiber's entire live state — program counter plus
variable frame — must be serializable at every block boundary. Design for it on
day one or never have it.

`Failure catch` gives per-fiber error handling; `Fiber stopped` lets one fiber
await another's termination.

---

## The expression language

Not a general scripting language — an *expression* language. It resolves to a
value; it has no statements. Three categories: arithmetic, text, logical.

Notable choices, all of which differ from the C/JS defaults an implementer will
reach for by reflex:

| | Automate | The reflex |
|---|---|---|
| Equality | `=` | `==` |
| Text concatenation | `++` | `+` |
| Comparison result | **number**, `1` or `0` | boolean |
| Integer division | `//` distinct from `/` | one operator |
| `10 / 0` | `Infinity` | error |
| `12 % 0` | `NaN` | error |
| `10n / 0n` (bigint) | **fails** | — |
| Mixing `bigint` and `number` in arithmetic | **fails** — must coerce explicitly | silent promotion |
| Mixing them in *comparison* | allowed | — |
| Comparing number with text | returns `0`, never an error | type error |
| `null` | compares as less than any non-null | varies |
| Text comparison | case-sensitive lexicographical | varies |

Operator classes: arithmetic (`+ - * / // % -`), bitwise (`& | ^ ~ << >> >>>`,
where `>>>` fails on bigint), comparison, logical, and "special" (`++`, the
to-number coercion operator).

The UI affordance is worth copying too: each input field has an **`fx` toggle**
that switches it between *constant mode* and *expression mode*. The user is
never made to escape or quote a literal to get one.

---

## What Masamune builds from this

**Rule 0 applies — port the donor faithfully first, differentiate after it
works.** The block grammar, the `Proceed` semantics, the three-section editor,
the fiber lifecycle and the operator table above are ported as specified, not
reinterpreted.

Then the departures, which are the point of doing this inside Masamune at all:

1. **The palette is not capped at what an app process can do.** Automate's 418
   blocks stop where Android's app sandbox stops. Masamune's flow plane sits on
   the per-app Termux prefix at `/data/local/tmp/masamune` running as uid 2000,
   so a block can invoke a real ELF binary from a real package manager. Automate
   needs `Shell command superuser` and a rooted device for a whole tier of
   things; Masamune reaches much of that tier at uid 2000 with no root at all.
2. **The AI operator is a fiber.** The LLM operator (task #78) is not a
   parallel subsystem bolted beside the flow plane — it drives the same graph
   runtime, so every action it takes is a block that can be inspected, logged,
   single-stepped and stopped. An operator that acts outside the graph is an
   operator the user cannot audit.
3. **n8n's canvas, Automate's semantics.** The user asked for an "n8n type flow
   plane and menus" — that is the *editor* ask: a zoomable canvas, a searchable
   categorized node palette, drag-to-connect. The execution model underneath it
   is Automate's, because Automate's is better suited to a phone.

### The honest-gating bound

`docs/DONOR-ASSETS.md`'s rule is not suspended here. A block whose payload or
permission is absent **reports absent** — disabled in the palette, with a
sentence naming what is missing. 418 blocks that render and do nothing is the
exact failure this campaign exists to remove, and a flow plane is a uniquely bad
place for it: a dead block does not fail visibly, it silently makes the rest of
the graph wrong.

The blocks that reach into **another app's** screen — `Interact`, `Interact
touch`, `Inspect layout`, `Inspect text edit`, `Key send`, `Key send
characters` — gate on the AccessibilityService being enabled. The `Interface *`
family (`Interface request`, `Interface clicked`, `Interface layout update`,
`Interface adapter update`, `Interface item request`) does **not**: verified
against the donor's own block pages, those drive Automate's *own* in-flow custom
UI and need no grant. An earlier draft here lumped the two together and was
wrong; the shipped catalog follows the donor pages, and this now matches it.
Blocks needing uid 2000 gate on the Yojimbo server being up. Neither is assumed.

---

## The full catalog — 418 blocks, 16 categories

Format: **display name** — `internal class`. The class name is Automate's own
identifier (from the documentation URL), and is the stable key to map a ported
block back to its origin.

### Apps  (`apps`) — 43 blocks

- **App decision** — `activity_start_result`
- **App start** — `activity_start`
- **App start voice** — `activity_start_voice`
- **ADB protocol set** — `adb_protocol_set`
- **ADB shell command** — `adb_shell_command`
- **Alternative launch** — `alternative_launch`
- **App clear cache** — `app_clear_cache`
- **App foreground** — `app_foreground`
- **App installed** — `app_installed`
- **App kill** — `app_kill`
- **App kill background** — `app_kill_background`
- **App list** — `app_list`
- **App notifications enabled** — `app_notifications_enabled`
- **App notifications set state** — `app_notifications_set_state`
- **App notifications priority get** — `app_notifications_priority_get`
- **App notifications priority set** — `app_notifications_priority_set`
- **App notifications visibility get** — `app_notifications_visibility_get`
- **App notifications visibility set** — `app_notifications_visibility_set`
- **App pick** — `app_pick`
- **App shortcut install** — `shortcut_pin`
- **App shortcut start** — `shortcut_start`
- **App shortcut update** — `shortcut_update`
- **App usage** — `app_usage`
- **AppOp mode set** — `app_op_mode_set`
- **AppOp mode** — `app_op_mode`
- **Broadcast decision** — `broadcast_send_ordered`
- **Broadcast receive** — `broadcast_receive`
- **Broadcast send** — `broadcast_send`
- **Google Assistant action** — `google_assistant_action`
- **Log await** — `log_await`
- **Plug-in action** — `plugin_setting`
- **Plug-in decision** — `plugin_condition`
- **Plug-in event** — `plugin_event`
- **Preferred activity** — `preferred_activity`
- **Profile quiet mode enabled** — `profile_quiet_mode_enabled`
- **Profile quiet mode request** — `profile_quiet_mode_request`
- **Resolve activity** — `resolve_activity`
- **Resolve receiver** — `resolve_receiver`
- **Resolve service** — `resolve_service`
- **Service start** — `service_start`
- **Shell command** — `shell_command`
- **Shell command privileged** — `shell_command_privileged`
- **Shell command superuser** — `shell_command_superuser`

### Battery & power  (`battery_and_power`) — 17 blocks

- **Battery charging** — `battery_charging`
- **Battery level** — `battery_level`
- **Battery properties** — `battery_properties`
- **Display power mode?** — `display_power_mode`
- **Display power mode set** — `display_power_mode_set`
- **Device doze mode active** — `device_idle_mode_active`
- **Device doze mode set state** — `device_idle_mode_set_state`
- **Device interactive** — `device_interactive`
- **Device keep awake** — `device_keep_awake`
- **Device reboot** — `device_reboot`
- **Device restart** — `device_restart`
- **Device shutdown** — `device_shutdown`
- **CPU speed get** — `cpu_speed_get`
- **CPU speed set** — `cpu_speed_set`
- **Power save mode enabled** — `power_save_mode_enabled`
- **Power save mode set state** — `power_save_mode_set_state`
- **Power source plugged** — `power_source_plugged`

### Camera & sound  (`camera_and_sound`) — 52 blocks

- **Audio device connected** — `audio_device_connected`
- **Audio device recording** — `audio_device_recording`
- **Audio player control** — `audio_player_control`
- **Audio record** — `audio_record_start`
- **Audio record stop** — `audio_record_stop`
- **Audio stream muted** — `audio_stream_muted`
- **Audio stream set mute** — `audio_stream_set_mute`
- **Audio volume** — `audio_volume`
- **Audio volume set** — `audio_volume_set`
- **Barcode scan** — `barcode_scan`
- **Bluetooth device active set** — `bluetooth_device_active_set`
- **Bluetooth SCO set state** — `bluetooth_sco_set_state`
- **Camera available** — `camera_available`
- **Capture image** — `capture_image`
- **Capture video** — `capture_video`
- **Flashlight enabled** — `flashlight_enabled`
- **Flashlight set state** — `flashlight_set_state`
- **Hotword detected** — `hotword_detected`
- **Image crop** — `image_crop`
- **Image flip** — `image_flip`
- **Image load** — `image_load`
- **Image rescale** — `image_rescale`
- **Image rotate** — `image_rotate`
- **Image sample color** — `image_sample_color`
- **Image unload** — `image_unload`
- **Image write** — `image_write`
- **Media playing** — `media_playing`
- **Media store add** — `media_store_add`
- **Media store remove** — `media_store_remove`
- **Media tags read** — `media_tags_read`
- **Microphone muted** — `microphone_muted`
- **Microphone set mute** — `microphone_set_mute`
- **QR code generate** — `qrcode_generate`
- **Ringtone pick** — `ringtone_pick`
- **Screenshot** — `screenshot`
- **Sound level** — `sound_level`
- **Sound play** — `sound_play`
- **Sound stop** — `sound_stop`
- **Speak** — `speak_play`
- **Speak stop** — `speak_stop`
- **Speak to file** — `speak_to_file`
- **Speakerphone on** — `speakerphone_on`
- **Speakerphone set state** — `speakerphone_set_state`
- **Speech recognition** — `speech_recognition`
- **Take picture** — `take_picture`
- **Text recognition** — `text_recognition`
- **Tone play** — `tone_play`
- **Tone stop** — `tone_stop`
- **Vibrate** — `vibrate_start`
- **Vibrate stop** — `vibrate_stop`
- **Video record** — `video_record_start`
- **Video record stop** — `video_record_stop`

### Concurrency  (`concurrency`) — 7 blocks

- **Atomic add & load** — `atomic_add`
- **Atomic compare & store** — `atomic_cas`
- **Atomic clear all** — `atomic_clear_all`
- **Atomic load** — `atomic_load`
- **Atomic store** — `atomic_store`
- **Variables give** — `variables_give`
- **Variables take** — `variables_take`

### Connectivity  (`connectivity`) — 51 blocks

- **Airplane mode enabled** — `airplane_mode_enabled`
- **Airplane mode set state** — `airplane_mode_set_state`
- **Bluetooth device pair** — `bluetooth_device_bond_create`
- **Bluetooth device unpair** — `bluetooth_device_bond_remove`
- **Bluetooth device connect** — `bluetooth_device_connect`
- **Bluetooth device connected** — `bluetooth_device_connected`
- **Bluetooth device disconnect** — `bluetooth_device_disconnect`
- **Bluetooth device pick** — `bluetooth_device_pick`
- **Bluetooth device scan** — `bluetooth_device_scan`
- **Bluetooth enabled** — `bluetooth_enabled`
- **Bluetooth set state** — `bluetooth_set_state`
- **Bluetooth GATT read** — `bluetooth_gatt_read`
- **Bluetooth tethering enabled** — `bluetooth_tether_enabled`
- **Bluetooth tethering set state** — `bluetooth_tether_set_state`
- **Data network default** — `data_network_default`
- **Data usage** — `data_usage`
- **Ethernet tethering set state** — `ethernet_tether_set_state`
- **HTTP accept** — `http_accept_tcp`
- **HTTP request** — `http_request`
- **HTTP response** — `http_response`
- **Infrared transmit** — `infrared_transmit`
- **Mobile data enabled** — `mobile_data_enabled`
- **Mobile data set state** — `mobile_data_set_state`
- **Mobile data network type** — `mobile_data_network_type`
- **Network connected** — `network_connected`
- **Network throughput** — `network_throughput`
- **Network service discover** — `nsd_discover`
- **Network type** — `network_type`
- **NFC enabled** — `nfc_enabled`
- **NFC set state** — `nfc_set_state`
- **NFC tag scanned** — `nfc_tag_scanned`
- **NFC tag write** — `nfc_tag_write`
- **Ping** — `ping`
- **Restrict background data enabled** — `restrict_background_data_enabled`
- **Restrict background data set state** — `restrict_background_data_set_state`
- **USB configuration set** — `usb_function_set`
- **USB configured** — `usb_configured`
- **USB device attached** — `usb_device_attached`
- **USB tethering enabled** — `usb_tether_enabled`
- **USB tethering set state** — `usb_tether_set_state`
- **Wake-on-LAN send** — `wake_on_lan_send`
- **Wi-Fi enabled** — `wifi_enabled`
- **Wi-Fi hotspot clients connected** — `wifi_ap_clients_connected`
- **Wi-Fi hotspot enabled** — `wifi_ap_enabled`
- **Wi-Fi hotspot set state** — `wifi_ap_set_state`
- **Wi-Fi network connect** — `wifi_network_connect`
- **Wi-Fi network connected** — `wifi_network_connected`
- **Wi-Fi network scan** — `wifi_network_scan`
- **Wi-Fi network pick** — `wifi_network_pick`
- **Wi-Fi set state** — `wifi_set_state`
- **Wi-Fi signal strength** — `wifi_signal_level`

### Content  (`content`) — 30 blocks

- **Account generic add** — `account_generic_add`
- **Account pick** — `account_pick`
- **Account sync request** — `account_sync_request`
- **Account sync enabled** — `account_sync_enabled`
- **Account sync set state** — `account_sync_set_state`
- **Alarm** — `alarm`
- **Alarm add** — `alarm_add`
- **Calendar event add** — `calendar_event_add`
- **Calendar event get** — `calendar_event_get`
- **Calendar event query** — `calendar_event_query`
- **Calendar pick** — `calendar_pick`
- **Contact query** — `contact_query`
- **Contact pick** — `contact_pick`
- **Content changed** — `content_changed`
- **Content delete** — `content_delete`
- **Content insert** — `content_insert`
- **Content offer** — `content_offer`
- **Content offer result** — `content_offer_result`
- **Content pick** — `content_pick`
- **Content provider call** — `content_provider_call`
- **Content query** — `content_query`
- **Content read** — `content_read`
- **Content shared** — `content_shared`
- **Content update** — `content_update`
- **Content view** — `content_view`
- **Content write** — `content_write`
- **Database modify** — `database_modify`
- **Database query** — `database_query`
- **Keychain credentials pick** — `keychain_alias_pick`
- **Timer add** — `timer_add`

### Date & time  (`date_and_time`) — 7 blocks

- **Delay** — `delay`
- **Date pick** — `date_pick`
- **Duration pick** — `duration_pick`
- **Time await** — `time_await`
- **Time pick** — `time_pick`
- **Time window** — `time_window`
- **Time zone get** — `time_zone_get`

### File & storage  (`storage`) — 36 blocks

- **FTP delete** — `ftp_delete`
- **FTP download** — `ftp_download`
- **FTP list** — `ftp_list`
- **FTP make directory** — `ftp_make_directory`
- **FTP upload** — `ftp_upload`
- **File APK extract** — `file_apk_extract`
- **File copy** — `file_copy`
- **File delete** — `file_delete`
- **File exists** — `file_exists`
- **File list** — `file_list`
- **File make directory** — `file_make_directory`
- **File monitor** — `file_monitor`
- **File move** — `file_move`
- **File multipart extract** — `file_multipart_extract`
- **File pick** — `file_pick`
- **File read** — `file_read`
- **File write** — `file_write`
- **Google Drive delete** — `gdrive_delete`
- **Google Drive download** — `gdrive_download`
- **Google Drive file exists** — `gdrive_file_exists`
- **Google Drive list** — `gdrive_list`
- **Google Drive make directory** — `gdrive_make_directory`
- **Google Drive share** — `gdrive_share`
- **Google Drive upload** — `gdrive_upload`
- **OneDrive delete** — `onedrive_delete`
- **OneDrive download** — `onedrive_download`
- **OneDrive file exists** — `onedrive_file_exists`
- **OneDrive list** — `onedrive_list`
- **OneDrive make directory** — `onedrive_make_directory`
- **OneDrive upload** — `onedrive_upload`
- **Storage media list** — `storage_volume_list`
- **Storage media mounted** — `storage_media_mounted`
- **Storage space** — `storage_space`
- **Zip compress** — `zip_compress`
- **Zip extract** — `zip_extract`
- **Zip list** — `zip_list`

### Flow  (`flow`) — 13 blocks

- **Failure catch** — `failure_catch`
- **Fiber stop** — `fiber_stop`
- **Fiber stopped** — `fiber_stopped`
- **Flow beginning** — `flow_beginning`
- **Flow beginning pick** — `flow_beginning_pick`
- **Flow pick** — `flow_pick`
- **Flow start** — `flow_start`
- **Flow stop** — `flow_stop`
- **Fork** — `fork`
- **Go to** — `goto`
- **Label** — `label`
- **Log append** — `log_append`
- **Subroutine** — `subroutine`

### General  (`general`) — 10 blocks

- **Android version** — `android_version`
- **Array add** — `array_add`
- **Array remove** — `array_remove`
- **Array set** — `array_set`
- **Destructuring assign** — `destructuring_assign`
- **Dictionary put** — `dictionary_put`
- **Dictionary remove** — `dictionary_remove`
- **Expression true** — `expression_decision`
- **For each** — `for_each`
- **Variable set** — `variable_assign`

### Interface  (`interface`) — 66 blocks

- **Accessibility button** — `accessibility_button`
- **Assist request** — `assist_request`
- **Attention light** — `attention_light`
- **Car mode enabled** — `car_mode_enabled`
- **Car mode set state** — `car_mode_set_state`
- **Clipboard get** — `clipboard_get`
- **Clipboard set** — `clipboard_set`
- **Color pick** — `color_pick`
- **Device docked** — `device_docked`
- **Device lock** — `device_lock`
- **Device secure** — `device_secure`
- **Device unlocked** — `device_unlocked`
- **Dialog choice** — `dialog_choice`
- **Dialog confirm** — `dialog_confirm`
- **Dialog input** — `dialog_input`
- **Dialog message** — `dialog_message`
- **Dialog number** — `dialog_number`
- **Dialog web** — `dialog_web`
- **Display metrics get** — `display_metrics_get`
- **Display on** — `display_on`
- **Display query** — `display_query`
- **Feature usage** — `feature_usage`
- **Fingerprint gesture** — `fingerprint_gesture`
- **Floating button show** — `floating_button_show`
- **Fullscreen** — `fullscreen`
- **Hardware keyboard visible** — `hardware_keyboard_visible`
- **Icon pick** — `icon_pick`
- **Inspect layout** — `inspect_layout`
- **Inspect text edit** — `inspect_text_edit`
- **Interact** — `interact`
- **Interact touch** — `interact_touch`
- **Interface adapter update** — `interface_adapter_update`
- **Interface clicked** — `interface_clicked`
- **Interface item request** — `interface_item_request`
- **Interface layout update** — `interface_layout_update`
- **Interface request** — `interface_request`
- **Key pressed** — `key_pressed`
- **Key send** — `key_send`
- **Key send characters** — `key_send_characters`
- **Login failed** — `password_failed`
- **Media button** — `media_button`
- **Night mode enabled** — `night_mode_enabled`
- **Night mode set state** — `night_mode_set_state`
- **Notification action** — `notification_action`
- **Notification cancel** — `notification_cancel`
- **Notification channel pick** — `notification_channel_pick`
- **Notification interact** — `notification_interact`
- **Notification posted** — `notification_posted`
- **Notification show** — `notification_show`
- **Notification snooze** — `notification_snooze`
- **Process text selection** — `process_text`
- **Process text set** — `process_text_result`
- **Quick Settings tile show** — `quick_settings_tile_show`
- **Screen lock set state** — `screen_lock_set_state`
- **Screen orientation** — `screen_orientation`
- **Screen orientation set** — `screen_orientation_set`
- **Screensaver created** — `dream_created`
- **Screensaver setup** — `dream_setup`
- **Software keyboard visible** — `software_keyboard_visible`
- **Split-screen mode enabled** — `split_screen_mode_enabled`
- **Toast posted** — `toast_posted`
- **Toast show** — `toast_show`
- **Wallpaper created** — `wallpaper_created`
- **Wallpaper setup** — `wallpaper_setup`
- **Widget configure** — `appwidget_configure`
- **Wired headset plugged** — `wired_headset`

### Location  (`location`) — 10 blocks

- **Geocoding reverse** — `geocoding_reverse`
- **Geocoding** — `geocoding`
- **Location at** — `location_at`
- **Location get** — `location_get`
- **Location mock** — `location_mock`
- **Location pick** — `location_pick`
- **Location provider enabled** — `location_provider_enabled`
- **Location provider set state** — `location_provider_set_state`
- **Location show** — `location_show`
- **Weather** — `weather`

### Messaging  (`messaging`) — 12 blocks

- **Cloud message receive** — `cloud_message_receive`
- **Cloud message send** — `cloud_message_send`
- **Compose MMS** — `compose_mms`
- **Compose SMS** — `compose_sms`
- **Compose e-mail** — `compose_email`
- **E-mail send** — `email_send`
- **Gmail send** — `gmail_send`
- **Gmail unread count** — `gmail_unread_count`
- **MMS send** — `mms_send`
- **SMS received** — `sms_received`
- **SMS send** — `sms_send`
- **SMS sent** — `sms_sent`

### Sensor  (`sensor`) — 15 blocks

- **Ambient light** — `ambient_light`
- **Ambient temperature** — `ambient_temperature`
- **Atmospheric pressure** — `atmospheric_pressure`
- **Device acceleration** — `device_acceleration`
- **Device orientation** — `device_orientation`
- **Heart rate** — `heart_rate`
- **Device hinge angle** — `hinge_angle`
- **Magnetic field strength** — `magnetic_field_strength`
- **Motion gesture** — `motion_gesture`
- **Pedometer** — `pedometer`
- **Physical activity** — `physical_activity`
- **Proximity** — `proximity`
- **Relative humidity** — `relative_humidity`
- **Significant device motion** — `significant_device_motion`
- **User asleep** — `user_asleep`

### Settings  (`settings`) — 24 blocks

- **CyanogenMod profile** — `cm_profile`
- **CyanogenMod profile set** — `cm_profile_set`
- **Input method pick** — `input_method_pick`
- **Input method set** — `input_method_set`
- **Interruptions** — `interruption_filter`
- **Interruptions set** — `interruption_filter_set`
- **Notification policy get** — `notification_policy_get`
- **Notification policy set** — `notification_policy_set`
- **Ringer mode** — `ringer_mode`
- **Ringer mode set** — `ringer_mode_set`
- **Ringtone get** — `ringtone_get`
- **Ringtone set** — `ringtone_set`
- **Screen brightness** — `screen_brightness`
- **Screen brightness set** — `screen_brightness_set`
- **Screen off timeout** — `screen_off_timeout`
- **Screen off timeout set** — `screen_off_timeout_set`
- **System language get** — `system_language_get`
- **System language set** — `system_language_set`
- **System property get** — `system_property_get`
- **System setting get** — `system_setting_get`
- **System setting set** — `system_setting_set`
- **Wallpaper colors get** — `wallpaper_colors_get`
- **Wallpaper image set** — `wallpaper_image_set`
- **Wallpaper live set** — `wallpaper_live_set`

### Telephony  (`telephony`) — 25 blocks

- **Call answer** — `call_answer`
- **Call end** — `call_end`
- **Call incoming** — `call_incoming`
- **Call number** — `call_number`
- **Call outgoing** — `call_outgoing`
- **Call screening** — `call_screening`
- **Call screening response** — `call_screening_response`
- **Call state** — `call_state`
- **Cell signal strength** — `cell_signal_level`
- **Cell tower near** — `cell_site_near`
- **Cell tower pick** — `cell_site_pick`
- **Dial number** — `dial_number`
- **DTMF tone play** — `dtmf_tone_play`
- **DTMF tone stop** — `dtmf_tone_stop`
- **Mobile network preferred** — `mobile_network_preferred`
- **Mobile network preferred set** — `mobile_network_preferred_set`
- **Mobile operator** — `mobile_operator`
- **Mobile service state** — `mobile_service_state`
- **Subscription default get** — `subscription_default_get`
- **Subscription default set** — `subscription_default_set`
- **Subscription pick** — `subscription_pick`
- **Subscription set state** — `subscription_set_state`
- **Ringer silence** — `ringer_silence`
- **Roaming** — `roaming`
- **USSD request** — `ussd_request`

<!-- TOTAL 418 blocks in 16 categories -->

---

## Implementation contract for Masamune

Module root: `masamune-next/src/main/java/dev/pleiades/masamune/flow/`

```
flow/
  model/    BlockShape, BlockSpec, ArgSpec, OptionSpec, Port, FlowGraph, Connection
  catalog/  BlockCatalog — all 418 specs, grouped by the 16 categories above
  expr/     Value, Lexer, Parser, Expr, Evaluator
  runtime/  Fiber, FiberFrame, Scheduler, FiberStore, BlockRunner, BlockImpl
  ui/       FlowCanvas, BlockPalette, BlockEditor, FiberMonitor
```

### Non-negotiable semantics

These are the points a reimplementation gets wrong by reflex. Each has a test.

**Value model** — `Value` is a sealed type: `Num(Double)`, `BigInt(BigInteger)`,
`Text(String)`, `ArrayV`, `DictV`, `Null`. Not `Any?`.

**Operators** — `=` is equality (not `==`). `++` concatenates text. `//` is
integer division, distinct from `/`. Comparison yields `Num(1.0)`/`Num(0.0)`,
**never a Kotlin `Boolean`**.

**Arithmetic edge cases, exactly:**

| Expression | Result |
|---|---|
| `10 / 0` | `Num(Infinity)` |
| `12 % 0` | `Num(NaN)` |
| `10n / 0n` | evaluation **failure** |
| `10n / 3n` | `BigInt(3)` |
| `Num` ⊕ `BigInt` in arithmetic or bitwise | **failure** — never silently promote |
| `Num` vs `BigInt` in *comparison* | allowed |
| `Num` vs `Text` in comparison | `Num(0.0)` — not an error |
| `Null` vs anything non-null | compares **less** |
| `~0b1` | `0xFFFFFFFE` (32-bit) |
| `~0b1n` | `BigInt(-2)` — bitwise NOT is `-x-1` at every width, so `~1 = -2`; same value as the row above read as signed, not a different one |
| `>>>` on `BigInt` | **failure** |

Text comparison is case-sensitive lexicographical.

**Graph grammar** — a node is `Action` (n `IN`, one `OK`) or `Decision`
(n `IN`, `YES` + `NO`). No other shape exists. A connection from an output port
that is already connected replaces it; an output port may hold at most one edge.
An input port accepts many.

**Fiber** — `Fiber` holds a program counter (node id + which port it entered by)
and its **own** variable frame. It must be **fully serializable at every block
boundary**; `FiberStore` persists on each block transition and restores on
process start, resuming at the last block. `Fork` deep-copies the frame.
A fiber terminates on `Flow stop`/`Fiber stop`, on **reaching an unconnected
port**, or on error. A flow is stopped only when all its fibers are.

**Scheduling** — cooperative, single dispatch loop, **not one thread per
fiber**. Blocking work (disk, network, subprocess) goes to a bounded IO
dispatcher and suspends only that fiber.

**Three-section editor** — Options are compile-time enums; Input arguments are
expressions with an `fx` constant/expression toggle and are **optional with
documented defaults**; Output fields accept a bare variable name and reject
anything else at edit time.

### Gating

`BlockSpec.requires: Set<Requirement>` — `ACCESSIBILITY`, `UID2000`,
`NOTIFICATION_LISTENER`, `DEVICE_ADMIN`, `PAYLOAD(name)`. The palette renders an
unsatisfied block **disabled, with the requirement named in a visible sentence**,
and the editor refuses to place it. No block silently no-ops. A dead block in a
graph makes every downstream block wrong, so this is stricter here than
elsewhere in the suite, not looser.
