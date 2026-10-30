#include "ble.h"

#include <stdio.h>
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/semphr.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "dev.h"
#include "haptic.h"
#include "led.h"

#include "store/config/ble_store_config.h"

// NimBLE's bond store in NVS. Its header doesn't declare this; ESP-IDF's examples do the same.
void ble_store_config_init(void);

static const char *TAG = "ble";

#define DEVICE_NAME "Piatra"
// The standard device type scanners show (Bluetooth SIG "Appearance" values): Keyring.
#define APPEARANCE 0x0240
#define NO_CONNECTION 0xFFFF
// Leave a few of the stack's buffers free for what the phone sends us.
#define KEEP_FREE_BUFFERS 4
// Give up on a notification if the stack couldn't take it for this long.
#define NOTIFY_TIMEOUT_US (5 * 1000 * 1000)
// How long the 5-tap gesture opens pairing mode for (protocol section 7).
#define PAIRING_MODE_US (60 * 1000 * 1000)
// Manufacturer data: company ID 0xFFFF ("testing and private use", protocol section 8),
// then our 10-byte status.
#define COMPANY_ID 0xFFFF
// While quiet (recording): skip up to this many connection events in a row, so the board
// transmits about once a second instead of every 30 ms. It still wakes at once to send.
#define QUIET_LATENCY 29

// Our 128-bit UUIDs, cdf7000N-b272-4e8a-b578-c59339280171 (protocol section 2). NimBLE wants
// the bytes back to front (little-endian), so the "N" ends up near the end.
#define PIATRA_UUID(n) \
    BLE_UUID128_INIT(0x71, 0x01, 0x28, 0x39, 0x93, 0xc5, 0x78, 0xb5, 0x8a, 0x4e, 0x72, 0xb2, n, 0x00, 0xf7, 0xcd)

static const ble_uuid128_t service_uuid = PIATRA_UUID(0x01);
static const ble_uuid128_t control_uuid = PIATRA_UUID(0x02);
static const ble_uuid128_t data_uuid = PIATRA_UUID(0x03);
static const ble_uuid128_t dev_uuid = PIATRA_UUID(0x04);

static QueueHandle_t commands;
static SemaphoreHandle_t sent; // given each time the stack has sent a notification
static uint8_t own_addr_type;
// Written by NimBLE's task, read by the sync task. `volatile`: always read the real value.
static volatile uint16_t conn = NO_CONNECTION;
static uint16_t control_handle, data_handle, dev_handle; // filled in by NimBLE when the table is registered
// The connection is a laptop paired with the dev code (dev.c).
static volatile bool dev_link;

static uint8_t mfg_data[2 + BLE_STATUS_LEN] = { COMPANY_ID & 0xFF, COMPANY_ID >> 8 };
static portMUX_TYPE mfg_lock = portMUX_INITIALIZER_UNLOCKED; // two tasks touch mfg_data

// Pairing (protocol section 7). Written by NimBLE's task and the recorder, read by both.
static volatile int64_t pairing_until_us; // pairing mode after the gesture ends then; 0 = off
static volatile bool refused;              // this connection tried to pair outside pairing mode
static ble_addr_t new_peer;               // the device that just bonded
static bool new_peer_dev;                 // ... with the dev code
static esp_timer_handle_t after_pairing;  // finishes a pairing outside NimBLE's callbacks

// When to advertise (protocol section 8). Not all the time: the phone's system scan reports
// the board as "appeared" when advertising starts, and that wakes the app to sync.
static volatile bool has_pending;          // recordings waiting to sync
static volatile int64_t awake_until_us;    // a tap or the 30-min check-in: advertise until then
static volatile int64_t pending_until_us;  // recordings waiting: stay awake for them until then
static volatile bool advertising;          // what we last asked for, for the log
static volatile bool quiet;                // recording: no advertising, skip connection events
static volatile bool update_again;         // a parameter update was already under way: ask again after it

static void advertise(void);
static bool adv_active(void);
static void adv_stop(void);
static const char *phy_name(uint8_t phy);
static bool in_pairing_mode(void);

// A laptop paired with the dev code has an "authenticated" bond (a passkey was checked);
// a phone's Just Works bond never is. That's how the two are told apart.
static bool is_dev_bond(const ble_addr_t *peer) {
    struct ble_store_key_sec key = { .peer_addr = *peer };
    struct ble_store_value_sec value;
    return ble_store_read_peer_sec(&key, &value) == 0 && value.authenticated;
}

static int bonded_peers(ble_addr_t peers[MYNEWT_VAL(BLE_STORE_MAX_BONDS)]) {
    int count = 0;
    ble_store_util_bonded_peers(peers, &count, MYNEWT_VAL(BLE_STORE_MAX_BONDS));
    return count;
}

static int phone_bond_count(void) {
    ble_addr_t peers[MYNEWT_VAL(BLE_STORE_MAX_BONDS)];
    int count = bonded_peers(peers), phones = 0;
    for (int i = 0; i < count; i++) phones += !is_dev_bond(&peers[i]);
    return phones;
}

// New phone bonds are accepted when the board has none yet, or for 60 s after the gesture.
static bool in_pairing_mode(void) {
    return phone_bond_count() == 0 || esp_timer_get_time() < pairing_until_us;
}

// Outside pairing mode the board turns down every pairing request straight away ("not
// supported"), before any keys are swapped, so the phone has nothing to save and shows the
// pairing as failed. NimBLE's security level 1 does exactly that; 0 is its default.
// A bonded phone doesn't pair again, it just encrypts with its saved key, so it's unaffected.
//
// In dev mode's pairing window (dev.c) the board instead says it has a display: the laptop
// then asks for the 6-digit code ("passkey entry"), which makes its bond authenticated.
static void allow_pairing(void) {
    bool dev = dev_pairing_open();
    ble_hs_cfg.sm_sec_lvl = in_pairing_mode() || dev ? 0 : 1;
    ble_hs_cfg.sm_io_cap = dev ? BLE_HS_IO_DISPLAY_ONLY : BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_mitm = dev;
}

void ble_pairing_mode(void) {
    pairing_until_us = esp_timer_get_time() + PAIRING_MODE_US;
    allow_pairing();
    led_blink(500, PAIRING_MODE_US / 1000); // slow blink while pairing mode is open
    ESP_LOGI(TAG, "pairing mode for 60 s: the next device to pair replaces the old bonds");
    ble_update_advertising();
}

static bool should_advertise(void) {
    if (quiet) return false;
    return has_pending || in_pairing_mode() || dev_mode_active() || esp_timer_get_time() < awake_until_us;
}

// Starts or stops advertising to match should_advertise(). Called from several tasks; NimBLE's
// calls take its own lock, and starting twice only answers "already".
void ble_update_advertising(void) {
    if (!ble_hs_synced() || conn != NO_CONNECTION) return; // connected: no advertising anyway
    bool want = should_advertise();
    if (want == advertising && want == adv_active()) return;
    if (want) {
        advertise();
    } else {
        adv_stop();
    }
    if (want != advertising) {
        advertising = want;
        if (want) {
            ESP_LOGI(TAG, "advertising on (%s)", has_pending ? "recordings waiting"
                                                 : in_pairing_mode() ? "pairing mode"
                                                 : dev_mode_active() ? "dev mode" : "awake");
        } else {
            ESP_LOGI(TAG, "advertising off: nothing to sync");
        }
    }
}

void ble_format_state(char *out, size_t size) {
    int64_t now = esp_timer_get_time();
    int64_t awake_ms = (awake_until_us - now) / 1000, pending_ms = (pending_until_us - now) / 1000;
    snprintf(out, size, "ADV pending=%d pending_ms=%lld pairing=%d dev=%d awake_ms=%lld want=%d flag=%d active=%d conn=%u dev_link=%d synced=%d",
             has_pending, pending_ms > 0 ? pending_ms : 0, in_pairing_mode(), dev_mode_active(),
             awake_ms > 0 ? awake_ms : 0, should_advertise(), advertising, adv_active(), conn,
             dev_link, ble_hs_synced());
}

// Asks the phone for the connection latency that matches `quiet`, keeping its interval.
// The phone (the "central") decides; Android and BlueZ accept this.
static void request_latency(void) {
    uint16_t c = conn;
    struct ble_gap_conn_desc desc;
    if (c == NO_CONNECTION || ble_gap_conn_find(c, &desc) != 0) return;
    uint16_t latency = quiet ? QUIET_LATENCY : 0;
    if (desc.conn_latency == latency) return;
    // The link counts as lost after the supervision timeout (10 ms units). It must be longer
    // than two skipped stretches: (1 + latency) × interval (1.25 ms units) × 2.
    uint16_t needed = (1 + latency) * desc.conn_itvl / 4 + 50;
    struct ble_gap_upd_params params = {
        .itvl_min = desc.conn_itvl,
        .itvl_max = desc.conn_itvl,
        .latency = latency,
        .supervision_timeout = needed > 500 ? needed : 500, // at least 5 s, Android's default
    };
    int rc = ble_gap_update_params(c, &params);
    update_again = rc == BLE_HS_EALREADY; // e.g. a quick tap: on and off within one update
    if (rc != 0 && rc != BLE_HS_EALREADY) ESP_LOGW(TAG, "connection update not sent (rc %d)", rc);
}

void ble_quiet(bool on) {
    quiet = on;
    request_latency();
    ble_update_advertising();
}

void ble_set_pending(bool pending) {
    has_pending = pending;
    ble_update_advertising();
}

void ble_wake(int seconds) {
    int64_t until = esp_timer_get_time() + (int64_t)seconds * 1000 * 1000;
    if (until > awake_until_us) awake_until_us = until;
    ble_update_advertising();
}

void ble_advertise_pending(int seconds) {
    int64_t until = esp_timer_get_time() + (int64_t)seconds * 1000 * 1000;
    if (until > pending_until_us) pending_until_us = until;
}

bool ble_keeps_awake(void) {
    int64_t now = esp_timer_get_time();
    return conn != NO_CONNECTION || now < awake_until_us || now < pairing_until_us ||
           (has_pending && now < pending_until_us);
}

// NimBLE saves keys through this when a pairing finishes ("bonding"). It wraps NimBLE's own
// NVS store: the last check that a new bond is only kept in pairing mode, or, for a laptop
// that typed the code, in dev mode's pairing window.
// Reconnecting with an existing bond doesn't save anything, so it never gets here.
static int store_write(int obj_type, const union ble_store_value *val) {
    bool keys = obj_type == BLE_STORE_OBJ_TYPE_OUR_SEC || obj_type == BLE_STORE_OBJ_TYPE_PEER_SEC;
    bool allowed = in_pairing_mode() || (dev_pairing_open() && val->sec.authenticated);
    if (keys && !allowed) {
        if (!refused) ESP_LOGW(TAG, "pairing refused: not in pairing mode (5 taps opens it)");
        refused = true;
        esp_timer_start_once(after_pairing, 0); // drop the connection, from another task
        return BLE_HS_EREJECT;
    }
    if (obj_type == BLE_STORE_OBJ_TYPE_PEER_SEC) {
        new_peer = val->sec.peer_addr;
        new_peer_dev = val->sec.authenticated;
        esp_timer_start_once(after_pairing, 0);
    }
    return ble_store_config_write(obj_type, val);
}

// Runs on ESP-IDF's timer task just after a pairing (or a refused one): NimBLE's callbacks
// are the wrong place to delete bonds or end a connection.
static void on_after_pairing(void *arg) {
    if (refused) {
        ble_disconnect();
        return;
    }
    // A new phone replaces the old phone (protocol section 7), a new laptop the old laptop;
    // the other kind of bond stays.
    ble_addr_t peers[MYNEWT_VAL(BLE_STORE_MAX_BONDS)];
    int count = bonded_peers(peers);
    for (int i = 0; i < count; i++) {
        if (ble_addr_cmp(&peers[i], &new_peer) != 0 && is_dev_bond(&peers[i]) == new_peer_dev) {
            ble_gap_unpair(&peers[i]);
            ESP_LOGI(TAG, "forgot an old bond");
        }
    }
    if (!new_peer_dev) pairing_until_us = 0; // the dev window closes when the laptop connects
    allow_pairing();
    led_blink(100, 300); // stops the slow blink
    haptic_buzz(200);
    ESP_LOGI(TAG, "paired and bonded (%s) with %02x:%02x:%02x:%02x:%02x:%02x",
             new_peer_dev ? "laptop, dev code" : "phone", new_peer.val[5],
             new_peer.val[4], new_peer.val[3], new_peer.val[2], new_peer.val[1], new_peer.val[0]);
}

// A write to the dev characteristic. Its flags already demand a code-paired link, and such a
// link opens dev mode (on_gap_event).
static int on_dev_write(struct os_mbuf *om) {
    if (!dev_link) return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    static uint8_t bytes[512]; // only NimBLE's task gets here, one write at a time
    uint16_t len;
    ble_hs_mbuf_to_flat(om, bytes, sizeof(bytes), &len);
    return dev_write(bytes, len) ? 0 : BLE_ATT_ERR_UNLIKELY;
}

// NimBLE calls this when the phone writes to (or tries to read) a characteristic.
static int on_access(uint16_t conn_handle, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt, void *arg) {
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR && attr_handle == dev_handle) return on_dev_write(ctxt->om);
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR || attr_handle != control_handle) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (refused) return BLE_ATT_ERR_INSUFFICIENT_AUTHEN; // being dropped
    // The data comes in an "mbuf" (NimBLE's chain of buffers): copy it out flat.
    ble_command_t cmd = { .len = OS_MBUF_PKTLEN(ctxt->om) };
    uint16_t copied;
    ble_hs_mbuf_to_flat(ctxt->om, cmd.bytes, sizeof(cmd.bytes), &copied);
    if (xQueueSend(commands, &cmd, 0) != pdTRUE) {
        ESP_LOGW(TAG, "command dropped: the last one is still being handled");
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    return 0;
}

// The GATT table: one service, two characteristics (protocol section 2). NimBLE adds the
// CCCD descriptor (where the phone turns notifications on) to each one by itself.
// The _ENC flags (protocol section 7): writing a command, or turning notifications on,
// needs an encrypted link. A phone that isn't paired gets "insufficient encryption", which
// makes Android (and BlueZ) start pairing.
static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &control_uuid.u,
                .access_cb = on_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_NOTIFY |
                         BLE_GATT_CHR_F_NOTIFY_INDICATE_ENC,
                .val_handle = &control_handle,
            },
            {
                .uuid = &data_uuid.u,
                .access_cb = on_access,
                .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_NOTIFY_INDICATE_ENC,
                .val_handle = &data_handle,
            },
            {
                // Development (protocol section 12): only a link paired with the dev code
                // ("authenticated") may use it. Writes without response carry the firmware.
                .uuid = &dev_uuid.u,
                .access_cb = on_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_WRITE_AUTHEN |
                         BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_NOTIFY_INDICATE_AUTHEN,
                .val_handle = &dev_handle,
            },
            { 0 }, // end of the list
        },
    },
    { 0 },
};

// Connection events (GAP = the part of BLE about finding and connecting to devices).
static int on_gap_event(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            conn = event->connect.conn_handle;
            refused = false;
            allow_pairing(); // the 60 s window may have closed since the last connection
            xQueueReset(commands); // nothing left over from an earlier connection
            int8_t rssi = 0;
            ble_gap_conn_rssi(conn, &rssi);
            uint8_t tx_phy = 0, rx_phy = 0;
            ble_gap_read_le_phy(conn, &tx_phy, &rx_phy);
            adv_stop(); // the other advert would keep going: one connection at a time
            // No security request from our side: on an already encrypted link it made BlueZ
            // pair again. The _ENC flags make the phone encrypt (or pair) when it needs to.
            ESP_LOGI(TAG, "phone connected, signal %d dBm, %s PHY%s", rssi, phy_name(rx_phy),
                     in_pairing_mode() ? " (pairing mode)" : "");
        } else {
            ble_update_advertising();
        }
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        conn = NO_CONNECTION;
        if (dev_link) {
            dev_link = false;
            dev_link_down();
        }
        xSemaphoreGive(sent); // wake a waiting notify, so it sees the phone has gone
        ESP_LOGI(TAG, "phone disconnected (reason 0x%x)", event->disconnect.reason);
        ble_update_advertising();
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        ble_update_advertising();
        break;
    case BLE_GAP_EVENT_CONN_UPDATE: {
        struct ble_gap_conn_desc desc;
        if (event->conn_update.status == 0 && ble_gap_conn_find(event->conn_update.conn_handle, &desc) == 0) {
            ESP_LOGI(TAG, "connection: every %.2f ms, may skip %u, timeout %u ms", desc.conn_itvl * 1.25,
                     desc.conn_latency, desc.supervision_timeout * 10);
        } else {
            ESP_LOGW(TAG, "connection update failed (status %d)", event->conn_update.status);
        }
        if (update_again) {
            update_again = false;
            request_latency();
        }
        break;
    }
    case BLE_GAP_EVENT_PHY_UPDATE_COMPLETE:
        if (event->phy_updated.status == 0) ESP_LOGI(TAG, "now on the %s PHY", phy_name(event->phy_updated.rx_phy));
        break;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "MTU %u: up to %u bytes per notification", event->mtu.value, event->mtu.value - 3);
        break;
    case BLE_GAP_EVENT_SUBSCRIBE: {
        uint16_t h = event->subscribe.attr_handle;
        if (h == control_handle || h == data_handle) {
            ESP_LOGI(TAG, "notifications %s on %s", event->subscribe.cur_notify ? "on" : "off",
                     h == control_handle ? "control" : "data");
        }
        break;
    }
    case BLE_GAP_EVENT_ENC_CHANGE: {
        struct ble_gap_conn_desc desc;
        if (event->enc_change.status == 0 && ble_gap_conn_find(event->enc_change.conn_handle, &desc) == 0) {
            bool laptop = desc.sec_state.authenticated; // paired with the dev code
            ESP_LOGI(TAG, "link encrypted (%s%s)", desc.sec_state.bonded ? "bonded" : "not bonded",
                     laptop ? ", dev code" : "");
            if (laptop) {
                // The laptop that paired with the code (in an earlier dev mode) may come back
                // whenever the board advertises, and that opens dev mode again: the safety net
                // for a board that sleeps and has no USB.
                if (!dev_mode_active()) ESP_LOGI(TAG, "the dev-paired laptop is back: dev mode on");
                dev_link = true;
                dev_link_up();
            } else if (!desc.sec_state.bonded && !in_pairing_mode()) {
                // An unknown device that got through anyway (allow_pairing should stop it). Drop it.
                ESP_LOGW(TAG, "pairing refused: not in pairing mode (5 taps opens it)");
                refused = true;
                esp_timer_start_once(after_pairing, 0);
            }
        } else {
            ESP_LOGW(TAG, "encryption failed (status 0x%x)", event->enc_change.status);
        }
        break;
    }
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        // The phone forgot its bond with us but we still have one (e.g. "Forget device" on
        // the phone). In pairing mode, delete ours and let it pair again; otherwise refuse.
        if (!in_pairing_mode() && !dev_pairing_open()) {
            ESP_LOGW(TAG, "a bonded device wants to pair again: do 5 taps first");
            refused = true;
            esp_timer_start_once(after_pairing, 0); // drop it now, not after the 30 s idle timeout
            return BLE_GAP_REPEAT_PAIRING_IGNORE;
        }
        struct ble_gap_conn_desc desc;
        ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc);
        ble_store_util_delete_peer(&desc.peer_id_addr);
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    case BLE_GAP_EVENT_PASSKEY_ACTION:
        // Pairing in dev mode: the board "displays" its fixed code (from firmware/dev.secret)
        // and the laptop must type the same one.
        if (event->passkey.params.action == BLE_SM_IOACT_DISP && dev_pairing_open()) {
            struct ble_sm_io io = { .action = BLE_SM_IOACT_DISP, .passkey = DEV_PASSKEY };
            ble_sm_inject_io(event->passkey.conn_handle, &io);
        }
        break;
    case BLE_GAP_EVENT_NOTIFY_TX:
        xSemaphoreGive(sent);
        break;
    }
    return 0;
}

// Two adverts side by side (protocol section 8), both connectable, from the same
// address. Bluetooth 5 calls each one an advertising "instance".
// - ADV_LEGACY: the ordinary kind on the 1M PHY, for the laptop and any phone. Two 31-byte
//   packets: the advertisement carries the flags, our service UUID (so the phone can scan for
//   it) and the device type (25 of 31 bytes); the scan response carries the name and the
//   status (12 + 2 bytes of manufacturer data, 8 for the name: 22 of 31).
// - ADV_CODED: long range. The Coded PHY sends every bit 8 times over, so a ~4x weaker signal
//   still gets through, at 125 kbps. Extended adverts hold up to 251 bytes here but can't have
//   a scan response, so everything goes in the one packet (47 bytes).
#define ADV_LEGACY 0
#define ADV_CODED 1

static bool adv_active(void) {
    return ble_gap_ext_adv_active(ADV_LEGACY) || ble_gap_ext_adv_active(ADV_CODED);
}

static void adv_stop(void) {
    for (uint8_t i = ADV_LEGACY; i <= ADV_CODED; i++) {
        if (!ble_gap_ext_adv_active(i)) continue; // stopping a stopped one is an error
        int rc = ble_gap_ext_adv_stop(i);
        if (rc != 0 && rc != BLE_HS_EALREADY) ESP_LOGE(TAG, "advert %u didn't stop (rc %d)", i, rc);
    }
}

// Hands NimBLE one packet built from `fields` (NimBLE frees the buffer).
static int set_packet(uint8_t instance, bool scan_response, const struct ble_hs_adv_fields *fields) {
    struct os_mbuf *buf = os_msys_get_pkthdr(BLE_HS_ADV_MAX_SZ, 0);
    if (buf == NULL) return BLE_HS_ENOMEM;
    int rc = ble_hs_adv_set_fields_mbuf(fields, buf);
    if (rc != 0) {
        os_mbuf_free_chain(buf);
        return rc;
    }
    return scan_response ? ble_gap_ext_adv_rsp_set_data(instance, buf) : ble_gap_ext_adv_set_data(instance, buf);
}

// The packets that carry the status: both are rewritten whenever it changes.
static int set_status_packets(void) {
    uint8_t mfg[sizeof(mfg_data)];
    taskENTER_CRITICAL(&mfg_lock);
    memcpy(mfg, mfg_data, sizeof(mfg));
    taskEXIT_CRITICAL(&mfg_lock);
    struct ble_hs_adv_fields rsp = {
        .name = (const uint8_t *)DEVICE_NAME,
        .name_len = strlen(DEVICE_NAME),
        .name_is_complete = 1,
        .mfg_data = mfg,
        .mfg_data_len = sizeof(mfg),
    };
    int rc = set_packet(ADV_LEGACY, true, &rsp);
    struct ble_hs_adv_fields coded = rsp;
    coded.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    coded.uuids128 = &service_uuid;
    coded.num_uuids128 = 1;
    coded.uuids128_is_complete = 1;
    coded.appearance = APPEARANCE;
    coded.appearance_is_present = 1;
    if (rc == 0) rc = set_packet(ADV_CODED, false, &coded);
    return rc;
}

void ble_set_status(const uint8_t status[BLE_STATUS_LEN]) {
    taskENTER_CRITICAL(&mfg_lock);
    memcpy(mfg_data + 2, status, BLE_STATUS_LEN);
    taskEXIT_CRITICAL(&mfg_lock);
    if (ble_hs_synced()) set_status_packets(); // takes effect even while advertising
}

// Sets up both adverts once (they can only be changed while stopped); advertise() starts them.
static void configure_adverts(void) {
    // The ordinary advert every ~200 ms, so the phone finds it quickly in the short windows the
    // board is awake (the radio's share of the power is small next to the chip being awake).
    struct ble_gap_ext_adv_params legacy = {
        .connectable = 1, // anyone may connect
        .scannable = 1,
        .legacy_pdu = 1,
        .itvl_min = BLE_GAP_ADV_ITVL_MS(200),
        .itvl_max = BLE_GAP_ADV_ITVL_MS(250),
        .own_addr_type = own_addr_type,
        .primary_phy = BLE_HCI_LE_PHY_1M,
        .secondary_phy = BLE_HCI_LE_PHY_1M,
        .tx_power = 127, // no preference: the controller's +20 dBm
        .sid = ADV_LEGACY,
    };
    // A Coded advert is ~8 ms on air instead of ~1, so less often: ~8 mA while advertising.
    struct ble_gap_ext_adv_params coded = legacy;
    coded.scannable = 0;
    coded.legacy_pdu = 0;
    coded.itvl_min = BLE_GAP_ADV_ITVL_MS(300);
    coded.itvl_max = BLE_GAP_ADV_ITVL_MS(350);
    coded.primary_phy = BLE_HCI_LE_PHY_CODED;
    coded.secondary_phy = BLE_HCI_LE_PHY_CODED;
    coded.sid = ADV_CODED;

    adv_stop();
    int rc = ble_gap_ext_adv_configure(ADV_LEGACY, &legacy, NULL, on_gap_event, NULL);
    if (rc == 0) rc = ble_gap_ext_adv_configure(ADV_CODED, &coded, NULL, on_gap_event, NULL);
    struct ble_hs_adv_fields adv = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .uuids128 = &service_uuid,
        .num_uuids128 = 1,
        .uuids128_is_complete = 1,
        .appearance = APPEARANCE,
        .appearance_is_present = 1,
    };
    if (rc == 0) rc = set_packet(ADV_LEGACY, false, &adv);
    if (rc == 0) rc = set_status_packets();
    if (rc != 0) ESP_LOGE(TAG, "adverts not set up (rc %d)", rc);
}

static void advertise(void) {
    for (uint8_t i = ADV_LEGACY; i <= ADV_CODED; i++) {
        if (ble_gap_ext_adv_active(i)) continue;
        int rc = ble_gap_ext_adv_start(i, 0, 0); // until stopped
        if (rc != 0 && rc != BLE_HS_EALREADY) ESP_LOGE(TAG, "advert %u didn't start (rc %d)", i, rc);
    }
}

static const char *phy_name(uint8_t phy) {
    return phy == BLE_HCI_LE_PHY_CODED ? "Coded" : phy == BLE_HCI_LE_PHY_2M ? "2M" : "1M";
}

// Called once the stack and the radio are ready.
static void on_sync(void) {
    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &own_addr_type);
    // Wrap the bond store here, not in ble_start: starting the stack calls
    // ble_store_config_init again, which puts NimBLE's own write function back.
    ble_hs_cfg.store_write_cb = store_write;
    allow_pairing();
    configure_adverts();
    uint8_t addr[6];
    ble_hs_id_copy_addr(own_addr_type, addr, NULL);
    ESP_LOGI(TAG, "Bluetooth ready as %s, address %02x:%02x:%02x:%02x:%02x:%02x", DEVICE_NAME,
             addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
    ble_update_advertising();
}

static void on_reset(int reason) {
    ESP_LOGE(TAG, "BLE stack reset (reason %d)", reason);
}

static void host_task(void *arg) {
    nimble_port_run(); // runs NimBLE until it's stopped, i.e. for ever
    nimble_port_freertos_deinit();
}

void ble_start(QueueHandle_t command_queue) {
    commands = command_queue;
    sent = xSemaphoreCreateBinary();

    ESP_ERROR_CHECK(nimble_port_init()); // needs NVS, which store_init has opened
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    // Security (protocol section 7): "Just Works" pairing with LE Secure Connections and
    // bonding, but only in pairing mode (allow_pairing, which also sets the code for dev
    // mode). No screen or keys, so no PIN. Both sides hand over their encryption key (ENC)
    // and identity key (ID: lets us recognise a phone behind its changing address).
    // Legacy pairing is switched off in sdkconfig.defaults. Not sm_sc_only: in NimBLE that
    // also demands a PIN-checked ("authenticated") link, which Just Works can never be.
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_store_config_init(); // bonds live in NVS, so they survive restarts (see on_sync)

    esp_timer_create_args_t timer = { .callback = on_after_pairing, .name = "after_pairing" };
    esp_timer_create(&timer, &after_pairing);

    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
    ble_svc_gap_device_name_set(DEVICE_NAME);
    ble_svc_gap_device_appearance_set(APPEARANCE); // also readable after connecting

    nimble_port_freertos_init(host_task);
}

bool ble_connected(void) {
    return conn != NO_CONNECTION;
}

void ble_disconnect(void) {
    uint16_t c = conn;
    if (c != NO_CONNECTION) ble_gap_terminate(c, BLE_ERR_REM_USER_CONN_TERM);
}

int ble_rssi(void) {
    int8_t rssi = 0;
    uint16_t c = conn;
    if (c == NO_CONNECTION || ble_gap_conn_rssi(c, &rssi) != 0) return 0;
    return rssi;
}

size_t ble_max_payload(void) {
    uint16_t c = conn;
    return c == NO_CONNECTION ? 20 : ble_att_mtu(c) - 3;
}

// Flow control: when the stack is out of buffers, wait until it has sent something, then
// try again. That's how a GET goes as fast as the link allows without losing chunks.
static bool notify(uint16_t handle, const uint8_t *bytes, size_t len) {
    if (len > ble_max_payload()) ESP_LOGW(TAG, "notification of %u bytes cut to the MTU", len);
    int64_t start = esp_timer_get_time();
    while (esp_timer_get_time() - start < NOTIFY_TIMEOUT_US) {
        uint16_t c = conn;
        if (c == NO_CONNECTION) return false;
        if (os_msys_num_free() >= KEEP_FREE_BUFFERS) {
            struct os_mbuf *om = ble_hs_mbuf_from_flat(bytes, len);
            if (om) {
                int rc = ble_gatts_notify_custom(c, handle, om); // takes om, even when it fails
                if (rc == 0) return true;
                if (rc != BLE_HS_ENOMEM) {
                    ESP_LOGW(TAG, "notify failed (rc %d)", rc);
                    return false;
                }
            }
        }
        xSemaphoreTake(sent, pdMS_TO_TICKS(10));
    }
    ESP_LOGW(TAG, "notify timed out");
    return false;
}

bool ble_notify_control(const uint8_t *bytes, size_t len) {
    return notify(control_handle, bytes, len);
}

bool ble_notify_data(const uint8_t *bytes, size_t len) {
    return notify(data_handle, bytes, len);
}

bool ble_notify_dev(const uint8_t *bytes, size_t len) {
    return notify(dev_handle, bytes, len);
}

bool ble_dev_connected(void) {
    return dev_link;
}

void ble_dev_mode_changed(void) {
    if (!ble_hs_synced()) return; // on_sync looks at dev mode itself
    allow_pairing();
    ble_update_advertising();
}
