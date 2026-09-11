package black.android.app;

import top.niunaijun.blackreflection.BlackReflection;
import top.niunaijun.blackreflection.utils.ClassUtil;

/* JADX INFO: loaded from: classes3.dex */
public class BRILocaleManagerStub {
    public static ILocaleManagerStubStatic getWithException() {
        return (ILocaleManagerStubStatic) BlackReflection.create(ILocaleManagerStubStatic.class, null, true);
    }

    public static ILocaleManagerStubStatic get() {
        return (ILocaleManagerStubStatic) BlackReflection.create(ILocaleManagerStubStatic.class, null, false);
    }

    public static ILocaleManagerStubContext getWithException(Object obj) {
        return (ILocaleManagerStubContext) BlackReflection.create(ILocaleManagerStubContext.class, obj, true);
    }

    public static ILocaleManagerStubContext get(Object obj) {
        return (ILocaleManagerStubContext) BlackReflection.create(ILocaleManagerStubContext.class, obj, false);
    }

    public static Class getRealClass() {
        return ClassUtil.classReady((Class<?>) ILocaleManagerStubContext.class);
    }
}
