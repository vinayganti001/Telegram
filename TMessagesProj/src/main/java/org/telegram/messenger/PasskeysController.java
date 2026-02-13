package org.telegram.messenger;

import android.content.Context;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.credentials.CreateCredentialResponse;
import androidx.credentials.CreatePublicKeyCredentialRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPublicKeyCredentialOption;
import androidx.credentials.PrepareGetCredentialResponse;
import androidx.credentials.exceptions.CreateCredentialCancellationException;
import androidx.credentials.exceptions.CreateCredentialCustomException;
import androidx.credentials.exceptions.CreateCredentialInterruptedException;
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import android.app.Activity;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.firebase.pnv.VerifiedPhoneNumberTokenResult;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.LaunchActivity;

import com.google.android.gms.tasks.Task;
import com.google.firebase.pnv.FirebasePhoneNumberVerification;
import com.google.firebase.pnv.VerificationSupportResult;

import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.concurrent.Executors;

import kotlin.Result;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.GlobalScope;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobCancellationException;

@RequiresApi(api = 28)
public class PasskeysController {

    private static final boolean ENABLE_FPNV_FALLBACK = true;

    public static void create(Context context, int currentAccount, Utilities.Callback2<TL_account.Passkey, String> done) {
        if (!BuildVars.SUPPORTS_PASSKEYS) return;

        final CredentialManager credentialManager = CredentialManager.create(context);
        final AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.showDelayed(500);

        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(
            new TL_account.initPasskeyRegistration(),
            AndroidUtilities::runOnUIThread,
            (res, err) -> {
                progressDialog.dismiss();
                if (err != null) {
                    done.run(null, err.text);
                    return;
                }

                final String requestJson;
                try {
                    final JSONObject obj = new JSONObject(res.options.data);
                    final JSONObject publicKeyObj = obj.getJSONObject("publicKey");
                    requestJson = publicKeyObj.toString();
                } catch (Exception e) {
                    FileLog.e(e);
                    done.run(null, e.getMessage());
                    return;
                }

                final CreatePublicKeyCredentialRequest credentialRequest =
                    new CreatePublicKeyCredentialRequest(requestJson);

                try {
                    credentialManager.createCredential(context, credentialRequest, ktxCallback((res2, err2) -> {
                        if (err2 instanceof CreateCredentialCancellationException || err2 instanceof CreateCredentialInterruptedException) {
                            AndroidUtilities.runOnUIThread(() -> {
                                done.run(null, "CANCELLED");
                            });
                            return;
                        } else if (err2 instanceof CreateCredentialNoCreateOptionException) {
                            AndroidUtilities.runOnUIThread(() -> {
                                done.run(null, "EMPTY");
                            });
                            return;
                        } else if (err2 != null) {
                            FileLog.e(err2);
                            AndroidUtilities.runOnUIThread(() -> {
                                done.run(null, err2.getMessage());
                            });
                            return;
                        }

                        final TL_account.registerPasskey req2 = new TL_account.registerPasskey();

                        try {
                            final String responseJson = res2.getData().getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON");
                            final JSONObject json = new JSONObject(responseJson);

                            req2.credential = new TL_account.inputPasskeyCredentialPublicKey();
                            req2.credential.id = json.getString("id");
                            req2.credential.raw_id = json.getString("rawId");

                            final JSONObject response = json.getJSONObject("response");
                            final TL_account.inputPasskeyResponseRegister passkeyResponse = new TL_account.inputPasskeyResponseRegister();
                            passkeyResponse.client_data = new TLRPC.TL_dataJSON();
                            passkeyResponse.client_data.data = new String(Base64.decode(response.getString("clientDataJSON"), Base64.URL_SAFE));
                            passkeyResponse.attestation_object = Base64.decode(response.getString("attestationObject"), Base64.URL_SAFE);

                            FileLog.d("AAGUID: " + bytesToHex(Arrays.copyOfRange(passkeyResponse.attestation_object, 67, 67 + 16)));

                            req2.credential.response = passkeyResponse;
                        } catch (Exception e) {
                            FileLog.e(e);
                            AndroidUtilities.runOnUIThread(() -> {
                                done.run(null, e.getMessage());
                            });
                            return;
                        }

                        AndroidUtilities.runOnUIThread(() -> {
                            final AlertDialog progressDialog2 = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
                            progressDialog2.showDelayed(500);

                            final int requestId = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req2, AndroidUtilities::runOnUIThread, (passkey, err3) -> {
                                progressDialog2.dismiss();
                                if (err3 != null) {
                                    done.run(null, err3.text);
                                } else {
                                    done.run(passkey, null);
                                }
                            });
                            progressDialog2.setOnCancelListener(d -> {
                                ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
                                done.run(null, "CANCELLED");
                            });
                        });
                    }));
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> {
                        done.run(null, e.getMessage());
                    });
                }
            }
        );
    }

    public static Runnable login(Context context, int currentAccount, boolean clickedButton, Utilities.Callback3<Long, TLRPC.auth_Authorization, String> done) {
        return login(context, currentAccount, clickedButton, null, done);
    }

    public static Runnable login(Context context, int currentAccount, boolean clickedButton, Utilities.Callback<String> statusCallback, Utilities.Callback3<Long, TLRPC.auth_Authorization, String> done) {
        return login(context, currentAccount, clickedButton, statusCallback, null, done);
    }

    public static Runnable login(Context context, int currentAccount, boolean clickedButton, Utilities.Callback<String> statusCallback, Utilities.Callback3<Runnable, Runnable, String> consentHandler, Utilities.Callback3<Long, TLRPC.auth_Authorization, String> done) {
        if (!BuildVars.SUPPORTS_PASSKEYS) return null;

        final CredentialManager credentialManager = CredentialManager.create(context);

        final boolean[] cancelled = new boolean[1];
        final Runnable[] cancel = new Runnable[1];

        final TL_account.initPasskeyLogin req = new TL_account.initPasskeyLogin();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        final int requestId = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (cancelled[0]) return;
            if (res.options == null) {
                FileLog.d("PasskeysController: options is null");
                done.run(0L, null, "EMPTY");
                return;
            }
            if (err != null) {
                FileLog.e("PasskeysController: initPasskeyLogin error: " + err.text);
                done.run(0L, null, err.text);
                return;
            }
            FileLog.d("PasskeysController: initPasskeyLogin success, starting CredentialManager");

            final String requestJson;
            try {
                final JSONObject obj = new JSONObject(res.options.data);
                final JSONObject publicKeyObj = obj.getJSONObject("publicKey");
                requestJson = publicKeyObj.toString();
            } catch (Exception e) {
                FileLog.e(e);
                done.run(0L, null, e.getMessage());
                return;
            }

            final GetPublicKeyCredentialOption passkeyOption = new GetPublicKeyCredentialOption(requestJson);
            final GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(passkeyOption)
                    .setPreferImmediatelyAvailableCredentials(!clickedButton)
                    .build();

            try {
                final CancellationSignal cancellationSignal = new CancellationSignal();
                credentialManager.getCredentialAsync(context, request, cancellationSignal, context.getMainExecutor(), new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse res2) {
                        if (statusCallback != null) {
                            AndroidUtilities.runOnUIThread(() -> statusCallback.run("VerifyingPasskeys"));
                        }
                        final Credential credential = res2.getCredential();
                        FileLog.d("PasskeysController: onResult: CredentialManager success with credential type: " + credential.getClass().getName());

                        final int datacenterId;
                        final long userId;
                        final TL_account.finishPasskeyLogin req2 = new TL_account.finishPasskeyLogin();


                        try {
                            FileLog.d("PasskeysController: Parsing credential data...");
                            final String responseJson = credential.getData().getString("androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON");
                            final JSONObject json = new JSONObject(responseJson);

                            FileLog.d("PasskeysController: Constructing inputPasskeyCredentialPublicKey...");
                            req2.credential = new TL_account.inputPasskeyCredentialPublicKey();
                            TL_account.inputPasskeyCredentialPublicKey pubKeyCred = (TL_account.inputPasskeyCredentialPublicKey) req2.credential;
                            pubKeyCred.id = json.getString("id");
                            pubKeyCred.raw_id = json.getString("rawId");

                            final JSONObject response = json.getJSONObject("response");
                            final TL_account.inputPasskeyResponseLogin passkeyResponse = new TL_account.inputPasskeyResponseLogin();
                            passkeyResponse.client_data = new TLRPC.TL_dataJSON();
                            passkeyResponse.client_data.data = new String(Base64.decode(response.getString("clientDataJSON"), Base64.URL_SAFE));

                            passkeyResponse.authenticator_data = Base64.decode(response.getString("authenticatorData"), Base64.URL_SAFE);
                            passkeyResponse.signature = Base64.decode(response.getString("signature"), Base64.URL_SAFE);
                            passkeyResponse.user_handle = new String(Base64.decode(response.getString("userHandle"), Base64.URL_SAFE));

                            datacenterId = Integer.parseInt(passkeyResponse.user_handle.split(":")[0]);
                            userId = Long.parseLong(passkeyResponse.user_handle.split(":")[1]);

                            pubKeyCred.response = passkeyResponse;

                        } catch (Exception e) {
                            FileLog.e(e);
                            done.run(0L, null, e.getMessage());
                            return;
                        }

                        final AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
                        progressDialog.showDelayed(500);

                        if (datacenterId != ConnectionsManager.getInstance(currentAccount).getCurrentDatacenterId()) {
                            final int from_dc_id = ConnectionsManager.getInstance(currentAccount).getCurrentDatacenterId();
                            final long from_auth_key_id = ConnectionsManager.getInstance(currentAccount).getCurrentAuthKeyId();

                            ConnectionsManager.getInstance(currentAccount).setDefaultDatacenterId(datacenterId);

                            req2.flags |= TLObject.FLAG_0;
                            req2.from_dc_id = from_dc_id;
                            req2.from_auth_key_id = from_auth_key_id;
                        }

                        final int requestId = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req2, AndroidUtilities::runOnUIThread, (auth, err3) -> {
                            progressDialog.dismiss();
                            if (err3 != null) {
                                done.run(userId, null, err3.text);
                            } else {
                                done.run(userId, auth, null);
                            }
                        }, datacenterId, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagInvokeAfter);

                        progressDialog.setOnCancelListener(d -> {
                            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
                            done.run(userId, null, "CANCELLED");
                        });
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException err2) {
                        FileLog.e("PasskeysController: CredentialManager onError: " + err2.getClass().getName() + " - " + err2.getMessage());
                        try {
                            final JSONObject obj = new JSONObject(res.options.data);
                            final JSONObject publicKeyObj = obj.getJSONObject("publicKey");
                            final String challenge = publicKeyObj.getString("challenge");
                            
                            
                            if (ENABLE_FPNV_FALLBACK) {
                                startFirebasePhoneNumberVerification(context, currentAccount, cancelled, statusCallback, consentHandler, done, err2);
                            } else {
                                handleCredentialManagerError(cancelled, done, err2);
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                            handleCredentialManagerError(cancelled, done, err2);
                        }
                    }
                });

                cancel[0] = cancellationSignal::cancel;
            } catch (Exception e) {
                done.run(0L, null, e.getMessage());
            }

        }, ConnectionsManager.RequestFlagWithoutLogin);

        cancel[0] = () -> ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);

        return () -> {
            cancelled[0] = true;
            if (cancel[0] != null) {
                cancel[0].run();
            }
        };
    }

    public static <T> Continuation<T> ktxCallback(Utilities.Callback2<T, Throwable> done) {
        return ktxCallback(EmptyCoroutineContext.INSTANCE, done);
    }

    public static <T> Continuation<T> ktxCallback(CoroutineContext ctx, Utilities.Callback2<T, Throwable> done) {
        return new Continuation<T>() {
            @NonNull
            @Override
            public CoroutineContext getContext() {
                return ctx;
            }

            @Override
            public void resumeWith(@NonNull Object result) {
                if (result instanceof Result.Failure) {
                    done.run(null, ((Result.Failure) result).exception);
                } else {
                    done.run((T) result, null);
                }
            }
        };
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void startFirebasePhoneNumberVerification(Context context, int currentAccount, boolean[] cancelled, Utilities.Callback<String> statusCallback, Utilities.Callback3<Runnable, Runnable, String> consentHandler, Utilities.Callback3<Long, TLRPC.auth_Authorization, String> done, GetCredentialException originalError) {
        boolean googlePlayServicesAvailable = PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices();
        // if google play services are available and context is an activity, start firebase phone number verification
        if (!googlePlayServicesAvailable || !(context instanceof Activity)) {
            handleCredentialManagerError(cancelled, done, originalError);
            return;
        }

        if (statusCallback != null) {
            AndroidUtilities.runOnUIThread(() -> statusCallback.run("CheckingPhoneVerification"));
        }

        final Activity activity = (Activity) context;
        FirebasePhoneNumberVerification.getInstance(activity)
                .getVerificationSupportInfo()
                .addOnSuccessListener(supportResultList -> {
                    if (cancelled[0]) return;
                    boolean isSupported = supportResultList != null
                            && supportResultList.stream()
                            .anyMatch(VerificationSupportResult::isSupported);

                    if (isSupported) {
                        String carrierName = supportResultList.stream()
                                .filter(VerificationSupportResult::isSupported)
                                .map(VerificationSupportResult::getCarrierId)
                                .filter(name -> !name.isEmpty())
                                .distinct()
                                .collect(Collectors.joining(" or ")); // e.g. "Verizon" or "Verizon or T-Mobile"

                        Runnable onProceed = () -> {
                            FileLog.d("PasskeysController: Initiating FPNV fallback...");
                            if (statusCallback != null) {
                                AndroidUtilities.runOnUIThread(() -> statusCallback.run("VerifyingPhoneNumber"));
                            }
                            FirebasePhoneNumberVerification.getInstance(activity)
                                    .getVerifiedPhoneNumber()
                                    .addOnSuccessListener(result -> {
                                        if (cancelled[0]) return;
                                        try {
                                            String token = result.getToken();
                                            FileLog.d("PasskeysController: FPNV result phone number: " + result.getPhoneNumber());
                                            FileLog.d("PasskeysController: FPNV success, token: " + token);
                                            sendFinishPasskeyLoginRequest(context, currentAccount, token, cancelled, done);
                                        } catch (Exception e) {
                                            FileLog.e(e);
                                            if (!cancelled[0]) done.run(0L, null, e.getMessage());
                                        }
                                    })
                                    .addOnFailureListener(e -> {
                                        if (cancelled[0]) return;
                                        FileLog.e("PasskeysController: FPNV failed", e);
                                        handleCredentialManagerError(cancelled, done, originalError);
                                    });
                        };

                        Runnable onCancel = () -> {
                            FileLog.d("PasskeysController: User cancelled FPNV consent.");
                            handleCredentialManagerError(cancelled, done, originalError);
                        };

                        if (consentHandler != null) {
                            AndroidUtilities.runOnUIThread(() -> consentHandler.run(onProceed, onCancel, carrierName));
                        } else {
                           showConsentBottomSheet(activity, onProceed, onCancel);
                        }
                    } else {
                        FileLog.d("PasskeysController: FPNV not supported");
                        handleCredentialManagerError(cancelled, done, originalError);
                    }
                })
                .addOnFailureListener(e -> {
                    if (cancelled[0]) return;
                    FileLog.e("PasskeysController: getVerificationSupportInfo failed", e);
                    handleCredentialManagerError(cancelled, done, originalError);
                });
    }

    private static void showConsentBottomSheet(Activity activity, Runnable onProceed, Runnable onCancel) {
        org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(activity);
        
        android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));

        org.telegram.ui.Components.RLottieImageView lottieImageView = new org.telegram.ui.Components.RLottieImageView(activity);
        lottieImageView.setAnimation(R.raw.phone_flash_call, 100, 100);
        lottieImageView.playAnimation();
        lottieImageView.setAutoRepeat(true);
        container.addView(lottieImageView, org.telegram.ui.Components.LayoutHelper.createLinear(100, 100, android.view.Gravity.CENTER_HORIZONTAL, 0, 12, 0, 16));

        android.widget.TextView titleView = new android.widget.TextView(activity);
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(LocaleController.getString("FpnvConsentTitle", R.string.FpnvConsentTitle));
        titleView.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        titleView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        container.addView(titleView, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        android.widget.TextView messageView = new android.widget.TextView(activity);
        messageView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        messageView.setText(LocaleController.getString("FpnvConsentText", R.string.FpnvConsentText));
        messageView.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextGray2));
        messageView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        container.addView(messageView, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        boolean[] userResponded = new boolean[]{false};

        android.widget.TextView proceedButton = new android.widget.TextView(activity);
        proceedButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        proceedButton.setTypeface(AndroidUtilities.bold());
        proceedButton.setText(LocaleController.getString("OK", R.string.OK));
        proceedButton.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_buttonText));
        proceedButton.setBackground(org.telegram.ui.ActionBar.Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButton), org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButtonPressed)));
        proceedButton.setGravity(android.view.Gravity.CENTER);
        proceedButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        proceedButton.setOnClickListener(v -> {
            userResponded[0] = true;
            builder.getDismissRunnable().run();
            onProceed.run();
        });
        container.addView(proceedButton, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        builder.setCustomView(container);
        org.telegram.ui.ActionBar.BottomSheet sheet = builder.create();
        sheet.setOnDismissListener(dialog -> {
            if (!userResponded[0]) {
                FileLog.d("PasskeysController: Consent sheet dismissed without selection.");
                onCancel.run();
            }
        });
        sheet.show();

    }

    private static void sendFinishPasskeyLoginRequest(Context context, int currentAccount, String token, boolean[] cancelled, Utilities.Callback3<Long, TLRPC.auth_Authorization, String> done) {
        final TL_account.finishPasskeyLogin req = new TL_account.finishPasskeyLogin();
        TL_account.inputPasskeyCredentialFirebasePNV credential = new TL_account.inputPasskeyCredentialFirebasePNV();
        credential.pnv_token = token;
        req.credential = credential;

        final AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.showDelayed(500);

        final int requestId = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (auth, error) -> {
            progressDialog.dismiss();
            if (cancelled[0]) return;

            if (error != null) {
                done.run(0L, null, error.text);
            } else {
                if (auth instanceof TLRPC.TL_auth_authorization) {
                    done.run(((TLRPC.TL_auth_authorization) auth).user.id, auth, null);
                } else {
                    done.run(0L, auth, null);
                }
            }
        }, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagInvokeAfter);

        progressDialog.setOnCancelListener(dialog -> {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
            done.run(0L, null, "CANCELLED");
        });
    }

    private static void handleCredentialManagerError(boolean[] cancelled, Utilities.Callback3<Long, TLRPC.auth_Authorization, String> done, Throwable err2) {
        if (cancelled[0]) return;
        if (err2 instanceof NoCredentialException) {
            done.run(0L, null, "EMPTY");
        } else if (err2 instanceof GetCredentialCancellationException) {
            done.run(0L, null, "CANCELLED");
        } else if (err2 instanceof GetCredentialInterruptedException) {
            done.run(0L, null, "CANCELLED");
        } else if (err2 != null) {
            done.run(0L, null, err2.getMessage());
        }
    }
}
