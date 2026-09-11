package top.niunaijun.blackbox.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.util.Base64;
// import androidx.autofill.HintConstants;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes3.dex */
public class GoogleSignInHelper {
    public static final String ACTION_GSI_CANCEL = "com.zoro.engine.GSI_CANCEL";
    public static final String ACTION_GSI_TOKEN = "com.zoro.engine.GSI_TOKEN";
    private static final String BUNDLE_KEY_DISPLAY_NAME = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_DISPLAY_NAME";
    private static final String BUNDLE_KEY_FAMILY_NAME = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_FAMILY_NAME";
    private static final String BUNDLE_KEY_GIVEN_NAME = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_GIVEN_NAME";
    private static final String BUNDLE_KEY_ID = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID";
    private static final String BUNDLE_KEY_ID_TOKEN = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN";
    private static final String BUNDLE_KEY_PROFILE_PICTURE_URI = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_PROFILE_PICTURE_URI";
    private static final String BUNDLE_KEY_SUBTYPE = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE";
    private static final String TOKEN_FILE = "gsi_cached_token";
    private static final String TYPE_GOOGLE_ID_TOKEN = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL";
    private static final String TYPE_SIWG = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL";

    public static void cacheToken(Context context, String str) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(new File(context.getFilesDir(), TOKEN_FILE));
            fileOutputStream.write(str.getBytes("UTF-8"));
            fileOutputStream.close();
        } catch (Exception unused) {
        }
    }

    public static String getCachedIdToken(Context context) {
        try {
            File file = new File(context.getFilesDir(), TOKEN_FILE);
            if (!file.exists()) {
                return null;
            }
            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] bArr = new byte[(int) file.length()];
            fileInputStream.read(bArr);
            fileInputStream.close();
            String str = new String(bArr, "UTF-8");
            if (!isTokenExpired(str)) {
                return str;
            }
            file.delete();
        } catch (Exception unused) {
        }
        return null;
    }

    public static boolean isTokenExpired(String str) {
        try {
            String[] strArrSplit = str.split("\\.");
            if (strArrSplit.length < 2) {
                return true;
            }
            return new JSONObject(new String(Base64.decode(strArrSplit[1], 9))).optLong("exp", 0L) * 1000 < System.currentTimeMillis();
        } catch (Exception unused) {
            return true;
        }
    }

    public static void deliverTokenViaCallback(Object obj, String str) throws Exception {
        String[] strArrSplit = str.split("\\.");
        if (strArrSplit.length < 2) {
            throw new IllegalArgumentException("Invalid JWT");
        }
        JSONObject jSONObject = new JSONObject(new String(Base64.decode(strArrSplit[1], 9)));
        String strOptString = jSONObject.optString(NotificationCompat.CATEGORY_EMAIL, jSONObject.optString("sub", ""));
        String strOptString2 = jSONObject.optString("name", "");
        String strOptString3 = jSONObject.optString("given_name", "");
        String strOptString4 = jSONObject.optString("family_name", "");
        String strOptString5 = jSONObject.optString("picture", "");
        Bundle bundle = new Bundle();
        bundle.putString(BUNDLE_KEY_ID, strOptString);
        bundle.putString(BUNDLE_KEY_ID_TOKEN, str);
        bundle.putString(BUNDLE_KEY_DISPLAY_NAME, strOptString2);
        bundle.putString(BUNDLE_KEY_GIVEN_NAME, strOptString3);
        bundle.putString(BUNDLE_KEY_FAMILY_NAME, strOptString4);
        bundle.putString(BUNDLE_KEY_SUBTYPE, TYPE_SIWG);
        if (!strOptString5.isEmpty()) {
            bundle.putParcelable(BUNDLE_KEY_PROFILE_PICTURE_URI, Uri.parse(strOptString5));
        }
        Class<?> cls = Class.forName("android.credentials.Credential");
        int i = 0;
        Object objNewInstance = cls.getConstructor(String.class, Bundle.class).newInstance(TYPE_GOOGLE_ID_TOKEN, bundle);
        Class<?> cls2 = Class.forName("android.credentials.GetCredentialResponse");
        Object objNewInstance2 = cls2.getConstructor(cls).newInstance(objNewInstance);
        Method methodFindMethod = findMethod(obj.getClass(), "onResponse", cls2);
        if (methodFindMethod == null) {
            Method[] methods = obj.getClass().getMethods();
            int length = methods.length;
            while (true) {
                if (i >= length) {
                    break;
                }
                Method method = methods[i];
                if ("onResponse".equals(method.getName())) {
                    methodFindMethod = method;
                    break;
                }
                i++;
            }
        }
        if (methodFindMethod != null) {
            methodFindMethod.invoke(obj, objNewInstance2);
        }
    }

    public static void deliverTokenViaResultReceiver(ResultReceiver resultReceiver, String str, ClassLoader classLoader) throws Exception {
        Constructor<?> constructor;
        String[] strArrSplit = str.split("\\.");
        if (strArrSplit.length < 2) {
            throw new IllegalArgumentException("Invalid JWT");
        }
        JSONObject jSONObject = new JSONObject(new String(Base64.decode(strArrSplit[1], 9)));
        String strOptString = jSONObject.optString(NotificationCompat.CATEGORY_EMAIL, jSONObject.optString("sub", ""));
        String strOptString2 = jSONObject.optString("name", "");
        String strOptString3 = jSONObject.optString("given_name", "");
        String strOptString4 = jSONObject.optString("family_name", "");
        String strOptString5 = jSONObject.optString("picture", "");
        Class<?> clsLoadClass = classLoader.loadClass("com.google.android.gms.common.api.Status");
        Object objNewInstance = clsLoadClass.getDeclaredConstructor(Integer.TYPE).newInstance(0);
        Class<?> clsLoadClass2 = classLoader.loadClass("com.google.android.gms.auth.api.identity.SignInCredential");
        Constructor<?>[] declaredConstructors = clsLoadClass2.getDeclaredConstructors();
        int length = declaredConstructors.length;
        int i = 0;
        while (true) {
            if (i >= length) {
                constructor = null;
                break;
            }
            Constructor<?> constructor2 = declaredConstructors[i];
            if (constructor2.getParameterCount() == 9) {
                constructor = constructor2;
                break;
            }
            i++;
        }
        if (constructor == null) {
            throw new Exception("SignInCredential ctor not found");
        }
        constructor.setAccessible(true);
        Object objNewInstance2 = constructor.newInstance(strOptString, strOptString2, strOptString3, strOptString4, !strOptString5.isEmpty() ? Uri.parse(strOptString5) : null, null, str, null, null);
        byte[] bArrSafeParcelToBytes = safeParcelToBytes(objNewInstance, clsLoadClass);
        byte[] bArrSafeParcelToBytes2 = safeParcelToBytes(objNewInstance2, clsLoadClass2);
        Intent intent = new Intent();
        intent.putExtra(NotificationCompat.CATEGORY_STATUS, bArrSafeParcelToBytes);
        intent.putExtra("sign_in_credential", bArrSafeParcelToBytes2);
        Bundle bundle = new Bundle();
        bundle.putBoolean("FAILURE_RESPONSE", false);
        bundle.putInt("ACTIVITY_REQUEST_CODE", 1);
        bundle.putParcelable("RESULT_DATA", intent);
        resultReceiver.send(-1, bundle);
    }

    public static void deliverError(Object obj, String str, String str2) {
        Method method;
        try {
            Method[] methods = obj.getClass().getMethods();
            int length = methods.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    method = null;
                    break;
                }
                method = methods[i];
                if ("onError".equals(method.getName())) {
                    break;
                } else {
                    i++;
                }
            }
            if (method != null) {
                method.invoke(obj, str, str2);
            }
        } catch (Exception unused) {
        }
    }

    public static void launchWebViewSignIn(Context context) {
        Intent intent = new Intent(context, (Class<?>) GoogleSignInWebViewActivity.class);
        intent.addFlags(268435456);
        context.startActivity(intent);
    }

    private static Method findMethod(Class<?> cls, String str, Class<?>... clsArr) {
        try {
            return cls.getMethod(str, clsArr);
        } catch (NoSuchMethodException unused) {
            return null;
        }
    }

    private static byte[] safeParcelToBytes(Object obj, Class<?> cls) throws Exception {
        Parcel parcelObtain = Parcel.obtain();
        cls.getMethod("writeToParcel", Parcel.class, Integer.TYPE).invoke(obj, parcelObtain, 0);
        byte[] bArrMarshall = parcelObtain.marshall();
        parcelObtain.recycle();
        return bArrMarshall;
    }
}
