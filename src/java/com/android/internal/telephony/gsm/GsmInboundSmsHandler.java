/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.gsm;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Message;
import android.provider.Telephony.Sms.Intents;

import com.android.internal.telephony.CellBroadcastServiceManager;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.InboundSmsHandler;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.SmsStorageMonitor;
import com.android.internal.telephony.VisualVoicemailSmsFilter;
import com.android.internal.telephony.uicc.UsimServiceTable;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * This class broadcasts incoming SMS messages to interested apps after storing them in
 * the SmsProvider "raw" table and ACKing them to the SMSC. After each message has been
 */
public class GsmInboundSmsHandler extends InboundSmsHandler {

    private static BroadcastReceiver sTestBroadcastReceiver;
    /** Handler for SMS-PP data download messages to UICC. */
    private final UsimDataDownloadHandler mDataDownloadHandler;

    // When TEST_MODE is on we allow the test intent to trigger an SMS CB alert
    private static boolean sEnableCbModule = false;
    private static final boolean TEST_MODE = true; //STOPSHIP if true
    private static final String TEST_ACTION = "com.android.internal.telephony.gsm"
            + ".TEST_TRIGGER_CELL_BROADCAST";
    private static final String TOGGLE_CB_MODULE = "com.android.internal.telephony.gsm"
            + ".TOGGLE_CB_MODULE";
    private CellBroadcastServiceManager mCellBroadcastServiceManager;

    /**
     * Create a new GSM inbound SMS handler.
     */
    private GsmInboundSmsHandler(Context context, SmsStorageMonitor storageMonitor,
            Phone phone) {
        super("GsmInboundSmsHandler", context, storageMonitor, phone, null);
        phone.mCi.setOnNewGsmSms(getHandler(), EVENT_NEW_SMS, null);
        mDataDownloadHandler = new UsimDataDownloadHandler(phone.mCi, phone.getPhoneId());
        mCellBroadcastServiceManager = new CellBroadcastServiceManager(context, phone);
        if (sEnableCbModule) {
            mCellBroadcastServiceManager.enable();
        } else {
            mCellBroadcastHandler = GsmCellBroadcastHandler.makeGsmCellBroadcastHandler(context,
                    phone);
        }

        if (TEST_MODE) {
            sTestBroadcastReceiver = new GsmCbTestBroadcastReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(TEST_ACTION);
            filter.addAction(TOGGLE_CB_MODULE);
            context.registerReceiver(sTestBroadcastReceiver, filter);
        }
    }


    /**
     * A broadcast receiver used for testing emergency cell broadcasts. To trigger test GSM cell
     * broadcasts with adb run e.g:
     *
     * adb shell am broadcast -a com.android.internal.telephony.gsm.TEST_TRIGGER_CELL_BROADCAST \
     * --es pdu_string  0000110011010D0A5BAE57CE770C531790E85C716CBF3044573065B9306757309707767 \
     * A751F30025F37304463FA308C306B5099304830664E0B30553044FF086C178C615E81FF09000000000000000 \
     * 0000000000000
     *
     * adb shell am broadcast -a com.android.internal.telephony.gsm.TEST_TRIGGER_CELL_BROADCAST \
     * --es pdu_string  0000110011010D0A5BAE57CE770C531790E85C716CBF3044573065B9306757309707767 \
     * A751F30025F37304463FA308C306B5099304830664E0B30553044FF086C178C615E81FF09000000000000000 \
     * 0000000000000 --ei phone_id 0
     *
     * adb shell am broadcast -a com.android.internal.telephony.gsm.TOGGLE_CB_MODULE
     *
     * adb shell am broadcast -a com.android.internal.telephony.gsm.TOGGLE_CB_MODULE -ez enable true
     */
    private class GsmCbTestBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            logd("Received test intent action=" + intent.getAction());
            if (intent.getAction() == TEST_ACTION) {
                byte[] smsPdu = intent.getByteArrayExtra("pdu");
                if (smsPdu == null) {
                    String pduString = intent.getStringExtra("pdu_string");
                    smsPdu = decodeHexString(pduString);
                }
                if (smsPdu == null) {
                    log("No pdu or pdu_string extra, ignoring CB test intent");
                    return;
                }

                // Return early if phone_id is explicilty included and does not match mPhone.
                // If phone_id extra is not included, continue.
                int phoneId = mPhone.getPhoneId();
                if (intent.getIntExtra("phone_id", phoneId) != phoneId) {
                    return;
                }
                Message m = Message.obtain();
                AsyncResult.forMessage(m, smsPdu, null);
                if (sEnableCbModule) {
                    mCellBroadcastServiceManager.sendMessageToHandler(m);
                } else {
                    m.setWhat(GsmCellBroadcastHandler.EVENT_NEW_SMS_MESSAGE);
                    mCellBroadcastHandler.sendMessage(m);
                }
            } else if (intent.getAction() == TOGGLE_CB_MODULE) {
                if (intent.hasExtra("enable")) {
                    sEnableCbModule = intent.getBooleanExtra("enable", false);
                } else {
                    sEnableCbModule = !sEnableCbModule;
                }
                if (sEnableCbModule) {
                    log("enabling CB module");
                    mPhone.mCi.unSetOnNewGsmBroadcastSms(mCellBroadcastHandler.getHandler());
                    mCellBroadcastServiceManager.enable();
                } else {
                    log("enabling legacy platform CB handling");
                    mCellBroadcastServiceManager.disable();
                    if (mCellBroadcastHandler == null) {
                        mCellBroadcastHandler =
                                GsmCellBroadcastHandler.makeGsmCellBroadcastHandler(context,
                                        mPhone);
                    }
                    mPhone.mCi.setOnNewGsmBroadcastSms(mCellBroadcastHandler.getHandler(),
                            GsmCellBroadcastHandler.EVENT_NEW_SMS_MESSAGE, null);
                }
            }
        }
    }

    private byte[] decodeHexString(String hexString) {
        if (hexString == null || hexString.length() % 2 == 1) {
            return null;
        }
        byte[] bytes = new byte[hexString.length() / 2];
        for (int i = 0; i < hexString.length(); i += 2) {
            bytes[i / 2] = hexToByte(hexString.substring(i, i + 2));
        }
        return bytes;
    }

    private byte hexToByte(String hexString) {
        int firstDigit = toDigit(hexString.charAt(0));
        int secondDigit = toDigit(hexString.charAt(1));
        return (byte) ((firstDigit << 4) + secondDigit);
    }

    private int toDigit(char hexChar) {
        int digit = Character.digit(hexChar, 16);
        if (digit == -1) {
            return 0;
        }
        return digit;
    }

    /**
     * Unregister for GSM SMS.
     */
    @Override
    protected void onQuitting() {
        mPhone.mCi.unSetOnNewGsmSms(getHandler());
        mCellBroadcastServiceManager.disable();
        mCellBroadcastHandler.dispose();

        if (DBG) log("unregistered for 3GPP SMS");
        super.onQuitting();     // release wakelock
    }

    /**
     * Wait for state machine to enter startup state. We can't send any messages until then.
     */
    public static GsmInboundSmsHandler makeInboundSmsHandler(Context context,
            SmsStorageMonitor storageMonitor, Phone phone) {
        GsmInboundSmsHandler handler = new GsmInboundSmsHandler(context, storageMonitor, phone);
        handler.start();
        return handler;
    }

    /**
     * Return true if this handler is for 3GPP2 messages; false for 3GPP format.
     *
     * @return false (3GPP)
     */
    @Override
    protected boolean is3gpp2() {
        return false;
    }

    /**
     * Handle type zero, SMS-PP data download, and 3GPP/CPHS MWI type SMS. Normal SMS messages
     * are handled by {@link #dispatchNormalMessage} in parent class.
     *
     * @param smsb the SmsMessageBase object from the RIL
     * @return a result code from {@link android.provider.Telephony.Sms.Intents},
     * or {@link Activity#RESULT_OK} for delayed acknowledgment to SMSC
     */
    @Override
    protected int dispatchMessageRadioSpecific(SmsMessageBase smsb) {
        SmsMessage sms = (SmsMessage) smsb;

        if (sms.isTypeZero()) {
            // Some carriers will send visual voicemail SMS as type zero.
            int destPort = -1;
            SmsHeader smsHeader = sms.getUserDataHeader();
            if (smsHeader != null && smsHeader.portAddrs != null) {
                // The message was sent to a port.
                destPort = smsHeader.portAddrs.destPort;
            }
            VisualVoicemailSmsFilter
                    .filter(mContext, new byte[][]{sms.getPdu()}, SmsConstants.FORMAT_3GPP,
                            destPort, mPhone.getSubId());
            // As per 3GPP TS 23.040 9.2.3.9, Type Zero messages should not be
            // Displayed/Stored/Notified. They should only be acknowledged.
            log("Received short message type 0, Don't display or store it. Send Ack");
            addSmsTypeZeroToMetrics();
            return Intents.RESULT_SMS_HANDLED;
        }

        // Send SMS-PP data download messages to UICC. See 3GPP TS 31.111 section 7.1.1.
        if (sms.isUsimDataDownload()) {
            UsimServiceTable ust = mPhone.getUsimServiceTable();
            return mDataDownloadHandler.handleUsimDataDownload(ust, sms);
        }

        boolean handled = false;
        if (sms.isMWISetMessage()) {
            updateMessageWaitingIndicator(sms.getNumOfVoicemails());
            handled = sms.isMwiDontStore();
            if (DBG) log("Received voice mail indicator set SMS shouldStore=" + !handled);
        } else if (sms.isMWIClearMessage()) {
            updateMessageWaitingIndicator(0);
            handled = sms.isMwiDontStore();
            if (DBG) log("Received voice mail indicator clear SMS shouldStore=" + !handled);
        }
        if (handled) {
            addVoicemailSmsToMetrics();
            return Intents.RESULT_SMS_HANDLED;
        }

        if (!mStorageMonitor.isStorageAvailable() &&
                sms.getMessageClass() != SmsConstants.MessageClass.CLASS_0) {
            // It's a storable message and there's no storage available.  Bail.
            // (See TS 23.038 for a description of class 0 messages.)
            return Intents.RESULT_SMS_OUT_OF_MEMORY;
        }

        return dispatchNormalMessage(smsb);
    }

    private void updateMessageWaitingIndicator(int voicemailCount) {
        // range check
        if (voicemailCount < 0) {
            voicemailCount = -1;
        } else if (voicemailCount > 0xff) {
            // TS 23.040 9.2.3.24.2
            // "The value 255 shall be taken to mean 255 or greater"
            voicemailCount = 0xff;
        }
        // update voice mail count in Phone
        mPhone.setVoiceMessageCount(voicemailCount);
    }

    /**
     * Send an acknowledge message.
     *
     * @param success indicates that last message was successfully received.
     * @param result result code indicating any error
     * @param response callback message sent when operation completes.
     */
    @Override
    protected void acknowledgeLastIncomingSms(boolean success, int result, Message response) {
        mPhone.mCi.acknowledgeLastIncomingGsmSms(success, resultToCause(result), response);
    }

    /**
     * Convert Android result code to 3GPP SMS failure cause.
     *
     * @param rc the Android SMS intent result value
     * @return 0 for success, or a 3GPP SMS failure cause value
     */
    private static int resultToCause(int rc) {
        switch (rc) {
            case Activity.RESULT_OK:
            case Intents.RESULT_SMS_HANDLED:
                // Cause code is ignored on success.
                return 0;
            case Intents.RESULT_SMS_OUT_OF_MEMORY:
                return CommandsInterface.GSM_SMS_FAIL_CAUSE_MEMORY_CAPACITY_EXCEEDED;
            case Intents.RESULT_SMS_GENERIC_ERROR:
            default:
                return CommandsInterface.GSM_SMS_FAIL_CAUSE_UNSPECIFIED_ERROR;
        }
    }

    /**
     * Add SMS of type 0 to metrics.
     */
    private void addSmsTypeZeroToMetrics() {
        mMetrics.writeIncomingSmsTypeZero(mPhone.getPhoneId(),
                android.telephony.SmsMessage.FORMAT_3GPP);
    }

    /**
     * Add voicemail indication SMS 0 to metrics.
     */
    private void addVoicemailSmsToMetrics() {
        mMetrics.writeIncomingVoiceMailSms(mPhone.getPhoneId(),
                android.telephony.SmsMessage.FORMAT_3GPP);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        if (mCellBroadcastServiceManager != null) {
            mCellBroadcastServiceManager.dump(fd, pw, args);
        }
    }
}
