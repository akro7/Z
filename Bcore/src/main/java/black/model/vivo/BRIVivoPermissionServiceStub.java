package black.model.vivo;

import top.niunaijun.blackreflection.BlackReflection;
import top.niunaijun.blackreflection.utils.ClassUtil;

/* JADX INFO: loaded from: classes3.dex */
public class BRIVivoPermissionServiceStub {
    public static IVivoPermissionServiceStubStatic getWithException() {
        return (IVivoPermissionServiceStubStatic) BlackReflection.create(IVivoPermissionServiceStubStatic.class, null, true);
    }

    public static IVivoPermissionServiceStubStatic get() {
        return (IVivoPermissionServiceStubStatic) BlackReflection.create(IVivoPermissionServiceStubStatic.class, null, false);
    }

    public static IVivoPermissionServiceStubContext getWithException(Object obj) {
        return (IVivoPermissionServiceStubContext) BlackReflection.create(IVivoPermissionServiceStubContext.class, obj, true);
    }

    public static IVivoPermissionServiceStubContext get(Object obj) {
        return (IVivoPermissionServiceStubContext) BlackReflection.create(IVivoPermissionServiceStubContext.class, obj, false);
    }

    public static Class getRealClass() {
        return ClassUtil.classReady((Class<?>) IVivoPermissionServiceStubContext.class);
    }
}
