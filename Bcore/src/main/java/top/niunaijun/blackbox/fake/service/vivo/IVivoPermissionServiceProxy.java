package top.niunaijun.blackbox.fake.service.vivo;

import android.os.Process;
import black.android.os.BRServiceManager;
import black.model.vivo.BRIVivoPermissionServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import java.lang.reflect.Method;

public class IVivoPermissionServiceProxy extends BinderInvocationStub {

    @Override
    public boolean isBadEnv() {
        return false;
    }

    public IVivoPermissionServiceProxy() {
        super(BRServiceManager.get().getService("vivo_permission_service"));
    }

    @Override
    protected Object getWho() {
        return BRIVivoPermissionServiceStub.get().asInterface(BRServiceManager.get().getService("vivo_permission_service"));
    }

    @Override
    protected void inject(Object obj, Object obj2) {
        replaceSystemService("vivo_permission_service");
    }

    private static void replaceLastUserId(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Integer) {
                args[i] = BActivityThread.getUserId();
                return;
            }
        }
    }

    @ProxyMethod("checkPermission")
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            if (objArr != null && objArr.length > 2 && objArr[2] instanceof Integer) {
                if (((Integer) objArr[2]).intValue() == Process.myUid()) {
                    objArr[2] = Integer.valueOf(BlackBoxCore.getHostUid());
                }
            }
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("getAppPermission")
    public static class getAppPermission extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("setAppPermission")
    public static class setAppPermission extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("setWhiteListApp")
    public static class setWhiteListApp extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("setBlackListApp")
    public static class setBlackListApp extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("noteStartActivityProcess")
    public static class noteStartActivityProcess extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("isBuildInThirdPartApp")
    public static class isBuildInThirdPartApp extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("checkDelete")
    public static class checkDelete extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            if (objArr != null && objArr.length > 1 && objArr[1] instanceof String) {
                objArr[1] = BlackBoxCore.getHostPkg();
            }
            replaceLastUserId(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("setOnePermission")
    public static class setOnePermission extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            replaceLastUserId(objArr);
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("setOnePermissionExt")
    public static class setOnePermissionExt extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            replaceLastUserId(objArr);
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }

    @ProxyMethod("isVivoImeiPkg")
    public static class isVivoImeiPkg extends MethodHook {
        @Override
        protected Object hook(Object obj, Method method, Object[] objArr) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(objArr);
            return method.invoke(obj, objArr);
        }
    }
}
