/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.stk;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.PhoneConstants;

/**
 * AlertDialog used for DISPLAY TEXT commands.
 *
 */
public class StkDialogActivity extends Activity {
    // members
    //private static final String className = new Object(){}.getClass().getEnclosingClass().getName();
    private static final String LOG_TAG = "StkDialogActivity";
    TextMessage mTextMsg = null;
    private int mSlotId = -1;
    private StkAppService appService = StkAppService.getInstance();
    // Determines whether Terminal Response (TR) has been sent
    private boolean mIsResponseSent = false;
    // Determines whether this is in the pending state.
    private boolean mIsPending = false;
    // Utilize AlarmManager for real-time countdown
    private static final String DIALOG_ALARM_TAG = LOG_TAG;
    private static final long NO_DIALOG_ALARM = -1;
    private long mAlarmTime = NO_DIALOG_ALARM;

    // Keys for saving the state of the dialog in the bundle
    private static final String TEXT_KEY = "text";
    private static final String ALARM_TIME_KEY = "alarm_time";
    private static final String RESPONSE_SENT_KEY = "response_sent";
    private static final String SLOT_ID_KEY = "slotid";
    private static final String PENDING = "pending";


    private AlertDialog mAlertDialog;

    /*UNISOC: Feature for orange Feature @{*/
    private Context mContext;
    private boolean mHomePressedFlg;
    /*UNISOC: @}*/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = getBaseContext();
        /*UNISOC: Feature for orange Feature patch SPCSS00430239 @{*/
        if (mContext.getResources().getBoolean(R.bool.config_support_authentification)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        /*UNISOC: @}*/

        /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intentFilter.addAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        /*UNISOC: @}*/
        /*UNISOC: Feature for orange Feature patch SPCSS00430235 @{*/
        intentFilter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intentFilter.addAction(StkAppService.CLOSE_DIALOG_ACTIVITY);
        /*UNISOC: @}*/
        mContext.registerReceiver(mReceiver, intentFilter);

        /*UNISOC: Feature bug @{*/
        Intent intent = getIntent();
        if (intent != null) {
            mSlotId = intent.getIntExtra(StkAppService.SLOT_ID, -1);
        } else {
            finish();
        }
        /*UNISOC: @}*/
        CatLog.d(LOG_TAG, "onCreate, sim id: " + mSlotId);

        // appService can be null if this activity is automatically recreated by the system
        // with the saved instance state right after the phone process is killed.
        if (appService == null) {
            CatLog.d(LOG_TAG, "onCreate - appService is null");
            finish();
            return;
        }

        // New Dialog is created - set to no response sent
        mIsResponseSent = false;

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);

        alertDialogBuilder.setPositiveButton(R.string.button_ok, new
                DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        CatLog.d(LOG_TAG, "OK Clicked!, mSlotId: " + mSlotId);
                        sendResponse(StkAppService.RES_ID_CONFIRM, true);
                    }
                });

        alertDialogBuilder.setNegativeButton(R.string.button_cancel, new
                DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog,int id) {
                        CatLog.d(LOG_TAG, "Cancel Clicked!, mSlotId: " + mSlotId);
                        sendResponse(StkAppService.RES_ID_CONFIRM, false);
                    }
                });

        alertDialogBuilder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        CatLog.d(LOG_TAG, "Moving backward!, mSlotId: " + mSlotId);
                        sendResponse(StkAppService.RES_ID_BACKWARD);
                    }
                });

        alertDialogBuilder.create();

        initFromIntent(getIntent());
        if (mTextMsg == null) {
            finish();
            return;
        }

        if (!mTextMsg.responseNeeded) {
            alertDialogBuilder.setNegativeButton(null, null);
            // Register the instance of this activity because the dialog displayed for DISPLAY TEXT
            // command with an immediate response object should disappear when the terminal receives
            // a subsequent proactive command containing display data.
            appService.getStkContext(mSlotId).setImmediateDialogInstance(this);
        }

        /*UNISOC: Feature for orange Feature SPCSS00430231 @{*/
        if (!(mContext.getResources().getBoolean(R.bool.config_support_authentification))) {
            alertDialogBuilder.setTitle(mTextMsg.title);
        }
        /*UNISOC: @}*/

        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.stk_msg_dialog, null);
        alertDialogBuilder.setView(dialogView);
        TextView tv = (TextView) dialogView.findViewById(R.id.message);
        ImageView iv = (ImageView) dialogView.findViewById(R.id.icon);
        /*UNISOC: Feature for orange Feature SPCSS00430231 @{*/
        if(!(mContext.getResources().getBoolean(R.bool.config_support_authentification))) {
            if (mTextMsg.icon != null) {
                iv.setImageBitmap(mTextMsg.icon);
            } else {
                iv.setVisibility(View.GONE);
            }
        } else {
            iv.setVisibility(View.GONE);
        }
        /*UNISOC: @}*/

        // Per spec, only set text if the icon is not provided or not self-explanatory
        if ((mTextMsg.icon == null || !mTextMsg.iconSelfExplanatory)
                && !TextUtils.isEmpty(mTextMsg.text)) {
            tv.setText(mTextMsg.text);
        } else {
            tv.setVisibility(View.GONE);
        }

        mAlertDialog = alertDialogBuilder.create();
        mAlertDialog.setCanceledOnTouchOutside(false);
        mAlertDialog.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        CatLog.d(LOG_TAG, "onResume - mIsResponseSent[" + mIsResponseSent +
                "], sim id: " + mSlotId);
        // The pending dialog is unregistered if this instance was registered as it before.
        setPendingState(false);

        /*
         * If the userClear flag is set and dialogduration is set to 0, the display Text
         * should be displayed to user forever until some high priority event occurs
         * (incoming call, MMI code execution etc as mentioned under section
         * ETSI 102.223, 6.4.1)
         */
        if (StkApp.calculateDurationInMilis(mTextMsg.duration) == 0 &&
                !mTextMsg.responseNeeded && mTextMsg.userClear) {
            CatLog.d(LOG_TAG, "User should clear text..showing message forever");
            return;
        }

        appService.setDisplayTextDlgVisibility(true, mSlotId);

        /*
         * When another activity takes the foreground, we do not want the Terminal
         * Response timer to be restarted when our activity resumes. Hence we will
         * check if there is an existing timer, and resume it. In this way we will
         * inform the SIM in correct time when there is no response from the User
         * to a dialog.
         */
        if (mAlarmTime == NO_DIALOG_ALARM) {
            startTimeOut();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        CatLog.d(LOG_TAG, "onPause, sim id: " + mSlotId);
        /* UNISOC: Feature bug @{ */
        if (appService == null) {
            finish();
            return;
        }

        if (appService.isCuccOperator()) {
            cancelTimeOut();
            // Add paramater to differ the user operate.
            if (!mIsResponseSent) {
                sendResponse(StkAppService.RES_ID_END_SESSION, false, mHomePressedFlg);
                mHomePressedFlg = false;
            }
            finish();
        } else {
            appService.setDisplayTextDlgVisibility(false, mSlotId);
        }
        /*UNISOC: @}*/

        /*
         * do not cancel the timer here cancelTimeOut(). If any higher/lower
         * priority events such as incoming call, new sms, screen off intent,
         * notification alerts, user actions such as 'User moving to another activtiy'
         * etc.. occur during Display Text ongoing session,
         * this activity would receive 'onPause()' event resulting in
         * cancellation of the timer. As a result no terminal response is
         * sent to the card.
         */
    }

    @Override
    protected void onStart() {
        CatLog.d(LOG_TAG, "onStart, sim id: " + mSlotId);
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        CatLog.d(LOG_TAG, "onStop - before Send CONFIRM false mIsResponseSent[" +
                mIsResponseSent + "], sim id: " + mSlotId);

        // Nothing should be done here if this activity is being finished or restarted now.
        if (isFinishing() || isChangingConfigurations()) {
            return;
        }

        /*UNISOC: Feature bug @{*/
        if (!mTextMsg.responseNeeded) {
            return;
        }
        /*UNISOC: @}*/

        // This is registered as the pending dialog as this was sent to the background.
        setPendingState(true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        CatLog.d(LOG_TAG, "onDestroy - mIsResponseSent[" + mIsResponseSent +
                "], sim id: " + mSlotId);

        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            mAlertDialog.dismiss();
            mAlertDialog = null;
        }

        if (appService == null) {
            return;
        }
        // if dialog activity is finished by stkappservice
        // when receiving OP_LAUNCH_APP from the other SIM, we can not send TR here
        // , since the dialog cmd is waiting user to process.
        if (!isChangingConfigurations()) {
            if (!mIsResponseSent && appService != null && !appService.isDialogPending(mSlotId)) {
                sendResponse(StkAppService.RES_ID_CONFIRM, false);
            }
        }
        cancelTimeOut();
        /*UNISOC: Feature for orange Feature @{*/
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        /*UNISOC: @}*/
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        CatLog.d(LOG_TAG, "onSaveInstanceState");

        outState.putParcelable(TEXT_KEY, mTextMsg);
        outState.putBoolean(RESPONSE_SENT_KEY, mIsResponseSent);
        outState.putLong(ALARM_TIME_KEY, mAlarmTime);
        outState.putInt(SLOT_ID_KEY, mSlotId);
        outState.putBoolean(PENDING, mIsPending);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        CatLog.d(LOG_TAG, "onRestoreInstanceState");

        mTextMsg = savedInstanceState.getParcelable(TEXT_KEY);
        mIsResponseSent = savedInstanceState.getBoolean(RESPONSE_SENT_KEY);
        mAlarmTime = savedInstanceState.getLong(ALARM_TIME_KEY, NO_DIALOG_ALARM);
        mSlotId = savedInstanceState.getInt(SLOT_ID_KEY);

        // The pending dialog must be replaced if the previous instance was in the pending state.
        if (savedInstanceState.getBoolean(PENDING)) {
            setPendingState(true);
        }

        if (mAlarmTime != NO_DIALOG_ALARM) {
            startTimeOut();
        }

    }

    @Override
    protected void onNewIntent(Intent intent) {
        CatLog.d(LOG_TAG, "onNewIntent - updating the same Dialog box");
        setIntent(intent);
    }

    @Override
    public void finish() {
        super.finish();
        // Unregister the instance for DISPLAY TEXT command with an immediate response object
        // as it is unnecessary to ask the service to finish this anymore.
        if ((appService != null) && (mTextMsg != null) && !mTextMsg.responseNeeded) {
            if (SubscriptionManager.isValidSlotIndex(mSlotId)) {
                appService.getStkContext(mSlotId).setImmediateDialogInstance(null);
            }
        }
    }

    private void setPendingState(boolean on) {
        if (mTextMsg.responseNeeded) {
            if (mIsPending != on) {
                appService.getStkContext(mSlotId).setPendingDialogInstance(on ? this : null);
                mIsPending = on;
            }
        }
    }

    private void sendResponse(int resId, boolean confirmed) {
        cancelTimeOut();

        if (mSlotId == -1) {
            CatLog.d(LOG_TAG, "sim id is invalid");
            return;
        }

        if (StkAppService.getInstance() == null) {
            CatLog.d(LOG_TAG, "Ignore response: id is " + resId);
            return;
        }

        CatLog.d(LOG_TAG, "sendResponse resID[" + resId + "] confirmed[" + confirmed + "]");

        if (mTextMsg.responseNeeded) {
            Bundle args = new Bundle();
            args.putInt(StkAppService.OPCODE, StkAppService.OP_RESPONSE);
            args.putInt(StkAppService.SLOT_ID, mSlotId);
            args.putInt(StkAppService.RES_ID, resId);
            args.putBoolean(StkAppService.CONFIRMATION, confirmed);
            startService(new Intent(this, StkAppService.class).putExtras(args));
            mIsResponseSent = true;
        }
        if (!isFinishing()) {
            finish();
        }

    }

    private void sendResponse(int resId) {
        sendResponse(resId, true);
    }

    private void initFromIntent(Intent intent) {

        if (intent != null) {
            mTextMsg = intent.getParcelableExtra("TEXT");
            mSlotId = intent.getIntExtra(StkAppService.SLOT_ID, -1);
        } else {
            finish();
        }

        CatLog.d(LOG_TAG, "initFromIntent - [" + (Build.IS_DEBUGGABLE ? mTextMsg : "********")
                + "], slot id: " + mSlotId);
    }

    private void cancelTimeOut() {
        if (mAlarmTime != NO_DIALOG_ALARM) {
            CatLog.d(LOG_TAG, "cancelTimeOut - slot id: " + mSlotId);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.cancel(mAlarmListener);
            mAlarmTime = NO_DIALOG_ALARM;
        }
    }

    private void startTimeOut() {
        // No need to set alarm if device sent TERMINAL RESPONSE already
        // and it is required to wait for user to clear the message.
        if (mIsResponseSent || (mTextMsg.userClear && !mTextMsg.responseNeeded)) {
            return;
        }

        if (mAlarmTime == NO_DIALOG_ALARM) {
            int duration = StkApp.calculateDurationInMilis(mTextMsg.duration);
            // If no duration is specified, the timeout set by the terminal manufacturer is applied.
            if (duration == 0) {
                if (mTextMsg.userClear) {
                    duration = StkApp.DISP_TEXT_WAIT_FOR_USER_TIMEOUT;
                } else {
                    duration = StkApp.DISP_TEXT_CLEAR_AFTER_DELAY_TIMEOUT;
                }
            }
            mAlarmTime = SystemClock.elapsedRealtime() + duration;
        }

        CatLog.d(LOG_TAG, "startTimeOut: " + mAlarmTime + "ms, slot id: " + mSlotId);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, mAlarmTime, DIALOG_ALARM_TAG,
                mAlarmListener, null);
    }

    private final AlarmManager.OnAlarmListener mAlarmListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    CatLog.d(LOG_TAG, "The alarm time is reached");
                    mAlarmTime = NO_DIALOG_ALARM;
                    sendResponse(StkAppService.RES_ID_TIMEOUT);
                }
            };

    /* UNISOC: Feature for Cucc function @{ */
    private void sendResponse(int resId, boolean confirmed, boolean homepressed) {
        if (mSlotId == -1) {
            CatLog.d(LOG_TAG, "sim id is invalid");
            return;
        }

        if (StkAppService.getInstance() == null) {
            CatLog.d(LOG_TAG, "Ignore response: id is " + resId);
            return;
        }

        CatLog.d(LOG_TAG, "sendResponse resID[" + resId + "] confirmed[" + confirmed + "]"+" homepressed ["+homepressed+"];");
        if (mTextMsg.responseNeeded) {
            Bundle args = new Bundle();
            args.putInt(StkAppService.OPCODE, StkAppService.OP_RESPONSE);
            args.putInt(StkAppService.RES_ID, resId);
            args.putBoolean(StkAppService.CONFIRMATION, confirmed);
            args.putInt(StkAppService.SLOT_ID, mSlotId);
            args.putBoolean(StkAppService.HOMEPRESSEDFLAG, homepressed);
            startService(new Intent(this, StkAppService.class).putExtras(args));
            mIsResponseSent = true;
        }

        if (!isFinishing()) {
            finish();
        }
    }
    /*UNISOC: @}*/

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            CatLog.d(LOG_TAG, "action: " + action);
            /*UNISOC: Feature for orange Feature patch SPCSS00430242 @{ */
            if (TextUtils.equals(action, Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                String reason = intent.getStringExtra(StkAppService.SYSTEM_REASON);
                CatLog.d(LOG_TAG, "reason: " + reason);
                if (TextUtils.equals(reason, StkAppService.SYSTEM_HOME_KEY)) {
                    mHomePressedFlg = true;
                    CatLog.d(LOG_TAG, "handleHomeKeyClick, ready to response");
                    sendResponse(StkAppService.RES_ID_CONFIRM, false);
                    finish();
                }
            } else if (action.equals(StkAppService.CLOSE_DIALOG_ACTIVITY)) {
                int slotId = intent.getIntExtra(StkAppService.SLOT_ID, -1);
                CatLog.d(LOG_TAG, "CLOSE_DIALOG_ACTIVITY, mIsResponseSent: " + mIsResponseSent +
                        " slotId:" + slotId);
                if (!mIsResponseSent && slotId != -1 && mSlotId == slotId) {
                    CatLog.d(LOG_TAG, "CLOSE_DIALOG_ACTIVITY finish");
                    if (mTextMsg.responseNeeded) {
                        sendResponse(StkAppService.RES_ID_CONFIRM, true);
                    }
                    finish();
                }
            /*UNISOC: @}*/
            /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
            } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                CatLog.d(LOG_TAG, "ACTION_AIRPLANE_MODE_CHANGED rcvd finish");
                sendResponse(StkAppService.RES_ID_CONFIRM, false);
                finishAndRemoveTask();
            } else if (action.equals(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED)) {
                int slotId = intent.getIntExtra(PhoneConstants.PHONE_KEY, 0);
                if (slotId != mSlotId) return;
                int state = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                        TelephonyManager.SIM_STATE_UNKNOWN);
                if (TelephonyManager.SIM_STATE_ABSENT == state) {
                    CatLog.d(LOG_TAG, "ACTION_SIM_CARD_STATE_CHANGED absent, finish");
                    cancelTimeOut();
                    if (appService != null && (appService.isAllOtherCardsAbsent(slotId)
                            || (!appService.isMainMenuExsit(slotId)))) {
                        finishAndRemoveTask();
                    } else {
                        finish();
                    }
                }
            }
            /*UNISOC: @}*/
        }
    };
}
