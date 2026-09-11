package black.model.vivo;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Method;
import top.niunaijun.blackreflection.annotation.BClassNameNotProcess;
import top.niunaijun.blackreflection.annotation.BMethodCheckNotProcess;

/* JADX INFO: loaded from: classes3.dex */
@BClassNameNotProcess("vivo.app.security.IVivoPermissionService$Stub")
public interface IVivoPermissionServiceStubStatic {
    @BMethodCheckNotProcess
    Method _check_asInterface(IBinder iBinder);

    IInterface asInterface(IBinder iBinder);
}
