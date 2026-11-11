# Incoming SMS fix

On the GSI I could send texts but never received any. rild logged `Received New GW SMS Message` and Android never heard about it.

Samsung's `libsec-ril` holds back unsolicited responses, new SMS included, until the framework says it's ready. One UI does that by calling `setVendorSpecificConfiguration` on `vendor.samsung.hardware.radio@2.2::ISehRadio` with `FW_READY=1`, which ends up in `VendorConfigManager::EnableUnsolResponse` inside the RIL. AOSP telephony doesn't know about that call, so a GSI never makes it.

`flip3_smsready` makes it. After boot, `SmsReady` runs as root through `app_process` and sends that transaction (hwbinder code 47, oneway) to `slot1` and `slot2`. It sends it again whenever rild restarts. Texts the RIL was holding came in right after the first send.

The module doesn't check what phone it's on, so it might help on other Samsung devices with `ISehRadio@2.2` in the vendor. I've only tried it on the Flip3.

The last run is logged in `/data/adb/modules/flip3_smsready/smsready.log`.
