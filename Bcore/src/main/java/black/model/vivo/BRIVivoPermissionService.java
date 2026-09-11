package black.model.vivo;

import top.niunaijun.blackreflection.BlackReflection;
import top.niunaijun.blackreflection.utils.ClassUtil;

/* JADX INFO: loaded from: classes3.dex */
public class BRIVivoPermissionService {
    public static IVivoPermissionServiceStatic getWithException() {
        return (IVivoPermissionServiceStatic) BlackReflection.create(IVivoPermissionServiceStatic.class, null, true);
    }

    public static IVivoPermissionServiceStatic get() {
        return (IVivoPermissionServiceStatic) BlackReflection.create(IVivoPermissionServiceStatic.class, null, false);
    }

    public static IVivoPermissionServiceContext getWithException(Object obj) {
        return (IVivoPermissionServiceContext) BlackReflection.create(IVivoPermissionServiceContext.class, obj, true);
    }

    public static IVivoPermissionServiceContext get(Object obj) {
        return (IVivoPermissionServiceContext) BlackReflection.create(IVivoPermissionServiceContext.class, obj, false);
    }

    public static Class getRealClass() {
        return ClassUtil.classReady((Class<?>) IVivoPermissionServiceContext.class);
    }
}
