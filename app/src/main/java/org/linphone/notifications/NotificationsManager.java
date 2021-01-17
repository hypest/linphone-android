/*
 * Copyright (c) 2010-2019 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.notifications;

import static android.content.Context.NOTIFICATION_SERVICE;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.service.notification.StatusBarNotification;
import java.util.HashMap;
import org.linphone.LinphoneManager;
import org.linphone.R;
import org.linphone.call.CallActivity;
import org.linphone.call.CallIncomingActivity;
import org.linphone.call.CallOutgoingActivity;
import org.linphone.compatibility.Compatibility;
import org.linphone.contacts.ContactsManager;
import org.linphone.contacts.LinphoneContact;
import org.linphone.core.Address;
import org.linphone.core.Call;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.tools.Log;
import org.linphone.dialer.DialerActivity;
import org.linphone.service.LinphoneService;
import org.linphone.settings.LinphonePreferences;
import org.linphone.utils.DeviceUtils;
import org.linphone.utils.ImageUtils;
import org.linphone.utils.LinphoneUtils;

public class NotificationsManager {
    private static final int SERVICE_NOTIF_ID = 1;
    private static final int MISSED_CALLS_NOTIF_ID = 2;

    private final Context mContext;
    private final NotificationManager mNM;
    private final HashMap<String, Notifiable> mChatNotifMap;
    private final HashMap<String, Notifiable> mCallNotifMap;
    private int mLastNotificationId;
    private final Notification mServiceNotification;
    private int mCurrentForegroundServiceNotification;
    private CoreListenerStub mListener;

    public NotificationsManager(Context context) {
        mContext = context;
        mChatNotifMap = new HashMap<>();
        mCallNotifMap = new HashMap<>();
        mCurrentForegroundServiceNotification = 0;

        mNM = (NotificationManager) mContext.getSystemService(NOTIFICATION_SERVICE);

        if (mContext.getResources().getBoolean(R.bool.keep_missed_call_notification_upon_restart)) {
            StatusBarNotification[] notifs = Compatibility.getActiveNotifications(mNM);
            if (notifs != null && notifs.length > 1) {
                for (StatusBarNotification notif : notifs) {
                    if (notif.getId() != MISSED_CALLS_NOTIF_ID) {
                        dismissNotification(notif.getId());
                    }
                }
            }
        } else {
            mNM.cancelAll();
        }

        mLastNotificationId = 5; // Do not conflict with hardcoded notifications ids !

        Compatibility.createNotificationChannels(mContext);

        Bitmap bm = null;
        try {
            bm = BitmapFactory.decodeResource(mContext.getResources(), R.mipmap.ic_launcher);
        } catch (Exception e) {
            Log.e(e);
        }

        Intent notifIntent = new Intent(mContext, DialerActivity.class);
        notifIntent.putExtra("Notification", true);
        addFlagsToIntent(notifIntent);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        mContext, SERVICE_NOTIF_ID, notifIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        mServiceNotification =
                Compatibility.createNotification(
                        mContext,
                        mContext.getString(R.string.service_name),
                        "",
                        R.drawable.linphone_notification_icon,
                        R.mipmap.ic_launcher,
                        bm,
                        pendingIntent,
                        Notification.PRIORITY_MIN,
                        true);

        mListener = new CoreListenerStub() {};
    }

    public void onCoreReady() {
        Core core = LinphoneManager.getCore();
        if (core != null) {
            core.addListener(mListener);
        }
    }

    public void destroy() {
        // mNM.cancelAll();
        // Don't use cancelAll to keep message notifications !
        // When a message is received by a push, it will create a LinphoneService
        // but it might be getting killed quite quickly after that
        // causing the notification to be missed by the user...
        Log.i("[Notifications Manager] Getting destroyed, clearing Service & Call notifications");

        if (mCurrentForegroundServiceNotification > 0) {
            mNM.cancel(mCurrentForegroundServiceNotification);
        }

        for (Notifiable notifiable : mCallNotifMap.values()) {
            mNM.cancel(notifiable.getNotificationId());
        }

        Core core = LinphoneManager.getCore();
        if (core != null) {
            core.removeListener(mListener);
        }
    }

    private void addFlagsToIntent(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }

    public void startForeground() {
        if (LinphoneService.isReady()) {
            Log.i("[Notifications Manager] Starting Service as foreground");
            LinphoneService.instance().startForeground(SERVICE_NOTIF_ID, mServiceNotification);
            mCurrentForegroundServiceNotification = SERVICE_NOTIF_ID;
        }
    }

    private void startForeground(Notification notification, int id) {
        if (LinphoneService.isReady()) {
            Log.i("[Notifications Manager] Starting Service as foreground while in call");
            LinphoneService.instance().startForeground(id, notification);
            mCurrentForegroundServiceNotification = id;
        }
    }

    public void stopForeground() {
        if (LinphoneService.isReady()) {
            Log.i("[Notifications Manager] Stopping Service as foreground");
            LinphoneService.instance().stopForeground(true);
            mCurrentForegroundServiceNotification = 0;
        }
    }

    public void removeForegroundServiceNotificationIfPossible() {
        if (LinphoneService.isReady()) {
            if (mCurrentForegroundServiceNotification == SERVICE_NOTIF_ID
                    && !isServiceNotificationDisplayed()) {
                Log.i(
                        "[Notifications Manager] Linphone has started after device boot, stopping Service as foreground");
                stopForeground();
            }
        }
    }

    public void sendNotification(int id, Notification notif) {
        Log.i("[Notifications Manager] Notifying " + id);
        mNM.notify(id, notif);
    }

    public void dismissNotification(int notifId) {
        Log.i("[Notifications Manager] Dismissing " + notifId);
        mNM.cancel(notifId);
    }

    private boolean isServiceNotificationDisplayed() {
        return LinphonePreferences.instance().getServiceNotificationVisibility();
    }

    public String getSipUriForNotificationId(int notificationId) {
        for (String addr : mChatNotifMap.keySet()) {
            if (mChatNotifMap.get(addr).getNotificationId() == notificationId) {
                return addr;
            }
        }
        return null;
    }

    public void displayCallNotification(Call call) {
        if (call == null) return;

        Class callNotifIntentClass = CallActivity.class;
        if (call.getState() == Call.State.IncomingReceived
                || call.getState() == Call.State.IncomingEarlyMedia) {
            callNotifIntentClass = CallIncomingActivity.class;
        } else if (call.getState() == Call.State.OutgoingInit
                || call.getState() == Call.State.OutgoingProgress
                || call.getState() == Call.State.OutgoingRinging
                || call.getState() == Call.State.OutgoingEarlyMedia) {
            callNotifIntentClass = CallOutgoingActivity.class;
        }
        Intent callNotifIntent = new Intent(mContext, callNotifIntentClass);
        callNotifIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        mContext, 0, callNotifIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Address address = call.getRemoteAddress();
        String addressAsString = address.asStringUriOnly();
        Notifiable notif = mCallNotifMap.get(addressAsString);
        if (notif == null) {
            notif = new Notifiable(mLastNotificationId);
            mLastNotificationId += 1;
            mCallNotifMap.put(addressAsString, notif);
        }

        int notificationTextId;
        int iconId;
        switch (call.getState()) {
            case Released:
            case End:
                if (mCurrentForegroundServiceNotification == notif.getNotificationId()) {
                    Log.i(
                            "[Notifications Manager] Call ended, stopping notification used to keep service alive");
                    // Call is released, remove service notification to allow for an other call to
                    // be service notification
                    stopForeground();
                }
                mNM.cancel(notif.getNotificationId());
                mCallNotifMap.remove(addressAsString);
                return;
            case Paused:
            case PausedByRemote:
            case Pausing:
                iconId = R.drawable.topbar_call_notification;
                notificationTextId = R.string.incall_notif_paused;
                break;
            case IncomingEarlyMedia:
            case IncomingReceived:
                iconId = R.drawable.topbar_call_notification;
                notificationTextId = R.string.incall_notif_incoming;
                break;
            case OutgoingEarlyMedia:
            case OutgoingInit:
            case OutgoingProgress:
            case OutgoingRinging:
                iconId = R.drawable.topbar_call_notification;
                notificationTextId = R.string.incall_notif_outgoing;
                break;
            default:
                if (call.getCurrentParams().videoEnabled()) {
                    iconId = R.drawable.topbar_videocall_notification;
                    notificationTextId = R.string.incall_notif_video;
                } else {
                    iconId = R.drawable.topbar_call_notification;
                    notificationTextId = R.string.incall_notif_active;
                }
                break;
        }

        if (notif.getIconResourceId() == iconId
                && notif.getTextResourceId() == notificationTextId) {
            // Notification hasn't changed, do not "update" it to avoid blinking
            return;
        } else if (notif.getTextResourceId() == R.string.incall_notif_incoming) {
            // If previous notif was incoming call, as we will switch channels, dismiss it first
            dismissNotification(notif.getNotificationId());
        }

        notif.setIconResourceId(iconId);
        notif.setTextResourceId(notificationTextId);
        Log.i(
                "[Notifications Manager] Call notification notifiable is "
                        + notif
                        + ", pending intent "
                        + callNotifIntentClass);

        LinphoneContact contact = ContactsManager.getInstance().findContactFromAddress(address);
        Uri pictureUri = contact != null ? contact.getThumbnailUri() : null;
        Bitmap bm = ImageUtils.getRoundBitmapFromUri(mContext, pictureUri);
        String name =
                contact != null
                        ? contact.getFullName()
                        : LinphoneUtils.getAddressDisplayName(address);
        boolean isIncoming = callNotifIntentClass == CallIncomingActivity.class;

        Notification notification;
        if (isIncoming) {
            notification =
                    Compatibility.createIncomingCallNotification(
                            mContext,
                            notif.getNotificationId(),
                            bm,
                            name,
                            addressAsString,
                            pendingIntent);
        } else {
            notification =
                    Compatibility.createInCallNotification(
                            mContext,
                            notif.getNotificationId(),
                            mContext.getString(notificationTextId),
                            iconId,
                            bm,
                            name,
                            pendingIntent);
        }

        // Don't use incoming call notification as foreground service notif !
        if (!isServiceNotificationDisplayed() && !isIncoming) {
            if (call.getCore().getCallsNb() == 0) {
                Log.i(
                        "[Notifications Manager] Foreground service mode is disabled, stopping call notification used to keep it alive");
                stopForeground();
            } else {
                if (mCurrentForegroundServiceNotification == 0) {
                    if (DeviceUtils.isAppUserRestricted(mContext)) {
                        Log.w(
                                "[Notifications Manager] App has been restricted, can't use call notification to keep service alive !");
                        sendNotification(notif.getNotificationId(), notification);
                    } else {
                        Log.i(
                                "[Notifications Manager] Foreground service mode is disabled, using call notification to keep it alive");
                        startForeground(notification, notif.getNotificationId());
                    }
                } else {
                    sendNotification(notif.getNotificationId(), notification);
                }
            }
        } else {
            sendNotification(notif.getNotificationId(), notification);
        }
    }

    public String getSipUriForCallNotificationId(int notificationId) {
        for (String addr : mCallNotifMap.keySet()) {
            if (mCallNotifMap.get(addr).getNotificationId() == notificationId) {
                return addr;
            }
        }
        return null;
    }
}
