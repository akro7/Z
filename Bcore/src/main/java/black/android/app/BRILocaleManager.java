package black.android.app;

import top.niunaijun.blackreflection.BlackReflection;
import top.niunaijun.blackreflection.utils.ClassUtil;

/* JADX INFO: loaded from: classes3.dex */
public class BRILocaleManager {
    public static ILocaleManagerStatic getWithException() {
        return (ILocaleManagerStatic) BlackReflection.create(ILocaleManagerStatic.class, null, true);
    }

    public static ILocaleManagerStatic get() {
        return (ILocaleManagerStatic) BlackReflection.create(ILocaleManagerStatic.class, null, false);
    }

    public static ILocaleManagerContext getWithException(Object obj) {
        return (ILocaleManagerContext) BlackReflection.create(ILocaleManagerContext.class, obj, true);
    }

    public static ILocaleManagerContext get(Object obj) {
        return (ILocaleManagerContext) BlackReflection.create(ILocaleManagerContext.class, obj, false);
    }

    public static Class getRealClass() {
        return ClassUtil.classReady((Class<?>) ILocaleManagerContext.class);
    }
}
