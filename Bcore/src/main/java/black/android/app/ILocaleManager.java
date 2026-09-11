package black.android.app;

import android.os.IBinder;
import android.os.IInterface;
import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

/* JADX INFO: loaded from: classes3.dex */
@BClassName("android.app.ILocaleManager")
public interface ILocaleManager {

    @BClassName("android.app.ILocaleManager$Stub")
    public interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder iBinder);
    }
}
