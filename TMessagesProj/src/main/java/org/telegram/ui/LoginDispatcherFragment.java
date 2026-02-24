package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;


import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PasskeysController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

public class LoginDispatcherFragment extends BaseFragment {

    private boolean loginStarted;

    private TextView statusTextView;
    private RLottieImageView lottieImageView;
    private TextView verifyButton;
    private TextView manualButton;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        lottieImageView = new RLottieImageView(context);
        lottieImageView.setAnimation(R.raw.passkey, 120, 120);
        lottieImageView.playAnimation();
        lottieImageView.setAutoRepeat(true);
        frameLayout.addView(lottieImageView, LayoutHelper.createFrame(120, 120, Gravity.CENTER, 0, 0, 0, 30));

        statusTextView = new TextView(context);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        statusTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusTextView.setText(LocaleController.getString("CheckingCredentials", R.string.CheckingCredentials));
        statusTextView.setGravity(Gravity.CENTER);
        statusTextView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        frameLayout.addView(statusTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 80, 0, 0));

        verifyButton = new TextView(context);
        verifyButton.setText(LocaleController.getString("VerifyNumber", R.string.VerifyNumber));
        verifyButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        verifyButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        verifyButton.setGravity(Gravity.CENTER);
        verifyButton.setTypeface(AndroidUtilities.bold());
        verifyButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        verifyButton.setVisibility(View.GONE);
        frameLayout.addView(verifyButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.LEFT, 16, 0, 16, 64));

        manualButton = new TextView(context);
        manualButton.setText(LocaleController.getString("EnterNumberManually", R.string.EnterNumberManually));
        manualButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        manualButton.setGravity(Gravity.CENTER);
        manualButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        manualButton.setVisibility(View.GONE);
        frameLayout.addView(manualButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 16, 0, 16, 24));

        fragmentView = frameLayout;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!loginStarted) {
            loginStarted = true;
            checkPasskeys();
        }
    }

    private boolean newAccountMode;

    public void setNewAccountMode(boolean newAccountMode) {
        this.newAccountMode = newAccountMode;
    }

    private void checkPasskeys() {
        PasskeysController.login(getParentActivity(), currentAccount, false, (status) -> {
            if (statusTextView != null) {
                int resId = R.string.CheckingCredentials;
                switch (status) {
                    case "VerifyingPasskeys":
                        resId = R.string.VerifyingPasskeys;
                        break;
                    case "CheckingPhoneVerification":
                        resId = R.string.CheckingPhoneVerification;
                        break;
                    case "VerifyingPhoneNumber":
                        resId = R.string.VerifyingPhoneNumber;
                        break;
                }
                statusTextView.setText(LocaleController.getString(status, resId));
            }
        }, (onProceed, onCancel, carrierName) -> {
             if (lottieImageView != null) {
                 lottieImageView.setAnimation(R.raw.phone_flash_call, 100, 100); 
                 lottieImageView.playAnimation();
             }
             if (statusTextView != null) {
                 if (carrierName != null && !carrierName.isEmpty()) {
                     statusTextView.setText(AndroidUtilities.replaceTags(LocaleController.formatString("FpnvConsentTextCarrier", R.string.FpnvConsentTextCarrier, carrierName)));
                 } else {
                     statusTextView.setText(LocaleController.getString("FpnvConsentText", R.string.FpnvConsentText));
                 }
             }
             if (verifyButton != null) {
                 verifyButton.setVisibility(View.VISIBLE);
                 verifyButton.setOnClickListener(v -> {
                     verifyButton.setVisibility(View.GONE);
                     manualButton.setVisibility(View.GONE);
                     onProceed.run();
                 });
             }
             if (manualButton != null) {
                 manualButton.setVisibility(View.VISIBLE);
                 manualButton.setOnClickListener(v -> onCancel.run());
             }
        }, (id, auth, error) -> {
            Bundle args = new Bundle();
            args.putBoolean("passkey_attempted", true);

            LoginActivity loginActivity;
            if (newAccountMode) {
                loginActivity = new LoginActivity(currentAccount, args);
            } else {
                loginActivity = new LoginActivity(args);
            }
            loginActivity.setPriorPasskeyRequested(true);
            
            if (id != 0) {
              if (auth instanceof TLRPC.TL_auth_authorizationSignUpRequired) {
                   presentFragment(loginActivity.onPasskeySignUpRequired((TLRPC.TL_auth_authorizationSignUpRequired) auth), true);
              } else if (auth != null) {
                   // Success
                   presentFragment(loginActivity.onPasskeyLoginSuccess(id, auth), true);
              } else {
                   // Should technically not happen if id != 0 usually, but fallback
                   presentFragment(loginActivity, true);
              }
            } else {
                // Fallback to LoginActivity
                presentFragment(loginActivity, true);
            }
        });
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return false;
    }
}
