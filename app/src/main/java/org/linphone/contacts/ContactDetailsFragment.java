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
package org.linphone.contacts;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import org.linphone.LinphoneManager;
import org.linphone.R;
import org.linphone.activities.MainActivity;
import org.linphone.contacts.views.ContactAvatar;
import org.linphone.core.PresenceBasicStatus;
import org.linphone.core.PresenceModel;
import org.linphone.core.ProxyConfig;
import org.linphone.settings.LinphonePreferences;
import org.linphone.utils.LinphoneUtils;

public class ContactDetailsFragment extends Fragment implements ContactsUpdatedListener {
    private LinphoneContact mContact;
    private TextView mOrganization;
    private RelativeLayout mWaitLayout;
    private LayoutInflater mInflater;
    private View mView;
    private boolean mDisplayChatAddressOnly = false;

    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mContact = (LinphoneContact) getArguments().getSerializable("Contact");
        if (mContact == null) {
            if (savedInstanceState != null) {
                mContact = (LinphoneContact) savedInstanceState.get("Contact");
            }
        }

        mInflater = inflater;
        mView = inflater.inflate(R.layout.contact, container, false);

        if (getArguments() != null) {
            mDisplayChatAddressOnly = getArguments().getBoolean("ChatAddressOnly");
        }

        mWaitLayout = mView.findViewById(R.id.waitScreen);
        mWaitLayout.setVisibility(View.GONE);

        ImageView editContact = mView.findViewById(R.id.editContact);
        editContact.setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ((ContactsActivity) getActivity()).showContactEdit(mContact);
                    }
                });

        if (mContact != null
                && getResources().getBoolean(R.bool.forbid_pure_linphone_contacts_edition)) {
            editContact.setVisibility(mContact.isAndroidContact() ? View.VISIBLE : View.GONE);
        }

        ImageView deleteContact = mView.findViewById(R.id.deleteContact);
        deleteContact.setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final Dialog dialog =
                                ((ContactsActivity) getActivity())
                                        .displayDialog(getString(R.string.delete_text));
                        Button delete = dialog.findViewById(R.id.dialog_delete_button);
                        Button cancel = dialog.findViewById(R.id.dialog_cancel_button);

                        delete.setOnClickListener(
                                new OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        mContact.delete();
                                        // To ensure removed contact won't appear in the contacts
                                        // list anymore
                                        ContactsManager.getInstance().fetchContactsAsync();
                                        ((ContactsActivity) getActivity()).goBack();
                                        dialog.dismiss();
                                    }
                                });

                        cancel.setOnClickListener(
                                new OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        dialog.dismiss();
                                    }
                                });
                        dialog.show();
                    }
                });

        mOrganization = mView.findViewById(R.id.contactOrganization);

        ImageView back = mView.findViewById(R.id.back);
        back.setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ((ContactsActivity) getActivity()).goBack();
                    }
                });
        back.setVisibility(
                getResources().getBoolean(R.bool.isTablet) ? View.INVISIBLE : View.VISIBLE);

        return mView;
    }

    @Override
    public void onContactsUpdated() {
        LinphoneContact contact =
                ContactsManager.getInstance().findContactFromAndroidId(mContact.getAndroidId());
        if (contact != null) {
            changeDisplayedContact(contact);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        ContactsManager.getInstance().addContactsListener(this);
        displayContact(mInflater, mView);
    }

    @Override
    public void onPause() {
        ContactsManager.getInstance().removeContactsListener(this);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("Contact", mContact);
    }

    private void changeDisplayedContact(LinphoneContact newContact) {
        mContact = newContact;
        displayContact(mInflater, mView);
    }

    @SuppressLint("InflateParams")
    private void displayContact(LayoutInflater inflater, View view) {
        if (mContact == null) return;
        ContactAvatar.displayAvatar(mContact, view.findViewById(R.id.avatar_layout));

        boolean isOrgVisible = LinphonePreferences.instance().isDisplayContactOrganization();
        if (mContact != null
                && mContact.getOrganization() != null
                && !mContact.getOrganization().isEmpty()
                && isOrgVisible) {
            mOrganization.setText(mContact.getOrganization());
        } else {
            mOrganization.setVisibility(View.GONE);
        }

        TextView contactName = view.findViewById(R.id.contact_name);
        contactName.setText(mContact.getFullName());
        mOrganization.setText(
                (mContact.getOrganization() != null) ? mContact.getOrganization() : "");

        TableLayout controls = view.findViewById(R.id.controls);
        controls.removeAllViews();
        for (LinphoneNumberOrAddress noa : mContact.getNumbersOrAddresses()) {
            boolean skip;
            View v = inflater.inflate(R.layout.contact_control_cell, null);

            String value = noa.getValue();
            String displayedNumberOrAddress = value;
            if (getResources()
                    .getBoolean(R.bool.only_show_address_username_if_matches_default_domain)) {
                displayedNumberOrAddress = LinphoneUtils.getDisplayableUsernameFromAddress(value);
            }

            TextView label = v.findViewById(R.id.address_label);
            if (noa.isSIPAddress()) {
                label.setText(R.string.sip_address);
                skip = getResources().getBoolean(R.bool.hide_contact_sip_addresses);
            } else {
                label.setText(R.string.phone_number);
                skip = getResources().getBoolean(R.bool.hide_contact_phone_numbers);
            }

            TextView tv = v.findViewById(R.id.numeroOrAddress);
            tv.setText(displayedNumberOrAddress);
            tv.setSelected(true);

            ProxyConfig lpc = LinphoneManager.getCore().getDefaultProxyConfig();
            if (lpc != null) {
                String username = lpc.normalizePhoneNumber(displayedNumberOrAddress);
                if (username != null) {
                    value = LinphoneUtils.getFullAddressFromUsername(username);
                }
            }

            v.findViewById(R.id.friendLinphone).setVisibility(View.GONE);
            if (mContact.getFriend() != null) {
                PresenceModel pm = mContact.getFriend().getPresenceModelForUriOrTel(noa.getValue());
                if (pm != null && pm.getBasicStatus().equals(PresenceBasicStatus.Open)) {
                    v.findViewById(R.id.friendLinphone).setVisibility(View.VISIBLE);
                } else {
                    if (getResources()
                            .getBoolean(R.bool.hide_numbers_and_addresses_without_presence)) {
                        skip = true;
                    }
                }
            }

            v.findViewById(R.id.inviteFriend).setVisibility(View.GONE);
            if (!noa.isSIPAddress()
                    && v.findViewById(R.id.friendLinphone).getVisibility() == View.GONE
                    && !getResources().getBoolean(R.bool.hide_invite_contact)) {
                v.findViewById(R.id.inviteFriend).setVisibility(View.VISIBLE);
                v.findViewById(R.id.inviteFriend).setTag(noa.getNormalizedPhone());
                v.findViewById(R.id.inviteFriend)
                        .setOnClickListener(
                                new OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        String number = (String) v.getTag();
                                        Intent smsIntent = new Intent(Intent.ACTION_SENDTO);
                                        smsIntent.putExtra("address", number);
                                        smsIntent.setData(Uri.parse("smsto:" + number));
                                        String text =
                                                getString(R.string.invite_friend_text)
                                                        .replace(
                                                                "%s",
                                                                getString(R.string.download_link));
                                        smsIntent.putExtra("sms_body", text);
                                        startActivity(smsIntent);
                                    }
                                });
            }

            String contactAddress = mContact.getContactFromPresenceModelForUriOrTel(noa.getValue());

            if (!mDisplayChatAddressOnly) {
                v.findViewById(R.id.contact_call)
                        .setOnClickListener(
                                new OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        String tag = (String) v.getTag();
                                        ((MainActivity) getActivity()).newOutgoingCall(tag);
                                    }
                                });
                if (contactAddress != null) {
                    v.findViewById(R.id.contact_call).setTag(contactAddress);
                } else {
                    v.findViewById(R.id.contact_call).setTag(value);
                }
            } else {
                v.findViewById(R.id.contact_call).setVisibility(View.GONE);
            }

            if (!skip) {
                controls.addView(v);
            }
        }
    }
}
