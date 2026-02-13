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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

public class LoginDispatcherFragment extends BaseFragment {

    private boolean loginStarted;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        RLottieImageView lottieImageView = new RLottieImageView(context);
        lottieImageView.setAnimation(R.raw.passkey, 120, 120);
        lottieImageView.playAnimation();
        lottieImageView.setAutoRepeat(true);
        frameLayout.addView(lottieImageView, LayoutHelper.createFrame(120, 120, Gravity.CENTER, 0, 0, 0, 30));

        TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textView.setText(LocaleController.getString("CheckingCredentials", R.string.CheckingCredentials));
        textView.setGravity(Gravity.CENTER);
        frameLayout.addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 80, 0, 0));

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
        PasskeysController.login(getParentActivity(), currentAccount, false, (id, auth, error) -> {
            Bundle args = new Bundle();
            args.putBoolean("passkey_attempted", true);

            LoginActivity loginActivity;
            if (newAccountMode) {
                loginActivity = new LoginActivity(currentAccount, args);
            } else {
                loginActivity = new LoginActivity(args);
            }
            loginActivity.setPriorPasskeyRequested(true);

            if (id != 0 && auth != null) {
                // Success
                presentFragment(loginActivity.onPasskeyLoginSuccess(id, auth), true);
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
