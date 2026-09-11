package top.niunaijun.blackbox.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.TrieTree;


@SuppressLint("SdCardPath")
public class IOCore {
    public static final String TAG = "IOCore";

    private static final IOCore sIOCore = new IOCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> sCachePackageRedirect = new HashMap<>();

    public static IOCore get() {
        return sIOCore;
    }

    
    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        NativeCore.addIORule(origPath, redirectPath);
    }

    public void addBlackRedirect(String path) {
        if (TextUtils.isEmpty(path))
            return;
        sBlackTree.add(path);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        if (path.contains("/blackbox/")) {
            return path;
        }
        String search = sBlackTree.search(path);
        if (!TextUtils.isEmpty(search))
            return search;

        
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(mRedirectMap.get(key)));

        return path;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;

        
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(rule.get(key)));

        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    

    public void enableRedirect(Context context) {
        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, BlackBoxCore.getUserId());
            int systemUserId = BlackBoxCore.getHostUserId();
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);

            
            File profilesRoot = new File(BEnvironment.getVirtualRoot(), "profiles");
            FileUtils.mkdirs(profilesRoot.getAbsolutePath());
            
            rule.put("/data/misc/profiles", profilesRoot.getAbsolutePath());

            File profilesCurDir = new File(profilesRoot, String.format("cur/%d/%s", BlackBoxCore.getUserId(), packageName));
            File profilesRefDir = new File(profilesRoot, String.format("ref/%d/%s", BlackBoxCore.getUserId(), packageName));
            FileUtils.mkdirs(profilesCurDir.getAbsolutePath());
            FileUtils.mkdirs(profilesRefDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/cur/%d/%s", BlackBoxCore.getUserId(), packageName), profilesCurDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/ref/%d/%s", BlackBoxCore.getUserId(), packageName), profilesRefDir.getAbsolutePath());

            if (BlackBoxCore.getContext().getExternalCacheDir() != null && context.getExternalCacheDir() != null) {
                File external = BEnvironment.getExternalUserDir(BlackBoxCore.getUserId());

                
                rule.put("/sdcard", external.getAbsolutePath());
                rule.put(String.format("/storage/emulated/%d", systemUserId), external.getAbsolutePath());

                blackRule.add("/sdcard/Pictures");
                blackRule.add(String.format("/storage/emulated/%d/Pictures", systemUserId));
            }
            // Check both the ClientConfiguration flag AND RuntimeFlags (Samurai pattern)
            android.MetaCore.RuntimeFlags.sHideRoot = BlackBoxCore.get().isHideRoot();
            if (android.MetaCore.RuntimeFlags.sHideRoot) {
                hideRoot(rule);
            }
            proc(rule);
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        for (String s : blackRule) {
            get().addBlackRedirect(s);
        }
        NativeCore.enableIO();
    }

    private void hideRoot(Map<String, String> rule) {
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
    }

    /**
     * Register /proc/self/maps redirect with a PRE-WRITTEN FILTERED FILE.
     *
     * Root cause of both issues (security detection + no menu):
     *
     * The old impl called FileUtils.mkdirs(targetPath), which creates targetPath as a
     * DIRECTORY. When the game opens "/proc/self/maps" the IO hook redirects to this
     * directory fd. Reading a directory fd returns EISDIR — no content.
     *
     * For Helium: occasionally skip-parses (detection evaded or fires nondeterministically).
     * For get8BPbase(): never finds libmain (returns 0) → all hooks fail → NO MENU.
     *
     * Fix: read /proc/<numericPid>/maps (numeric PID avoids our own redirect), filter
     * lines that reveal the virtual-env (loader pkg, niunaijun, /blackbox/, BCore),
     * write the sanitised content to a real FILE, then register the redirect.
     * Both Helium and get8BPbase() now read a clean, readable maps file.
     */
    private void proc(Map<String, String> rule) {
        int appPid = BlackBoxCore.getAppPid();
        int pid    = android.os.Process.myPid();
        String selfProc = "/proc/self/";
        String proc     = "/proc/" + pid + "/";

        // cmdline redirect (unchanged from original)
        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline",     cmdline);
        rule.put(selfProc + "cmdline", cmdline);

        // maps redirect — write a filtered copy first
        File procDir   = BEnvironment.getProcDir(appPid);
        FileUtils.mkdirs(procDir.getAbsolutePath());

        File mapsTarget = new File(procDir, "maps");
        writeFilteredMaps(pid, mapsTarget);
        rule.put(proc + "maps",     mapsTarget.getAbsolutePath());
        rule.put(selfProc + "maps", mapsTarget.getAbsolutePath());

        // status redirect
        File statusTarget = new File(procDir, "status");
        writeFilteredStatus(pid, statusTarget);
        rule.put(proc + "status",     statusTarget.getAbsolutePath());
        rule.put(selfProc + "status", statusTarget.getAbsolutePath());
    }

    private static void writeFilteredMaps(int pid, File destFile) {
        String hostPkg = BlackBoxCore.getHostPkg();
        java.io.BufferedReader br = null;
        java.io.PrintWriter    pw = null;
        try {
            br = new java.io.BufferedReader(
                     new java.io.FileReader("/proc/" + pid + "/maps"), 65536);
            pw = new java.io.PrintWriter(
                     new java.io.BufferedWriter(
                         new java.io.FileWriter(destFile), 65536));
            String line;
            while ((line = br.readLine()) != null) {
                if (shouldFilterMapsLine(line, hostPkg)) continue;
                pw.println(line);
            }
            android.util.Log.d(TAG, "Filtered maps → " + destFile + " (" + destFile.length() + " B)");
        } catch (Exception e) {
            android.util.Log.w(TAG, "writeFilteredMaps: " + e.getMessage());
            try { destFile.createNewFile(); } catch (Exception ignored) {}
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
            if (pw != null) pw.close();
        }
    }

    private static boolean shouldFilterMapsLine(String line, String hostPkg) {
        if (!line.contains("/")) return false;
        if (!TextUtils.isEmpty(hostPkg) && line.contains(hostPkg)) return true;
        if (line.contains("niunaijun"))    return true;
        if (line.contains("/blackbox/"))   return true;
        if (line.contains("Bcore"))        return true;
        if (line.contains("BCore"))        return true;
        if (line.contains("VirtualApp"))   return true;
        if (line.contains("virtualapp"))   return true;
        if (line.contains("/sandbox/"))    return true;
        // Additional: strip loader package and mod library traces
        if (line.contains("com.fs4ip"))    return true;
        if (line.contains("akroengine"))   return true;
        if (line.contains("libakro"))      return true;
        if (line.contains("samuraiengine")) return true;
        if (line.contains("black_box"))    return true;
        return false;
    }

    private static void writeFilteredStatus(int pid, File destFile) {
        java.io.BufferedReader br = null;
        java.io.PrintWriter    pw = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid + "/status"), 8192);
            pw = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(destFile), 8192));
            String line;
            while ((line = br.readLine()) != null) pw.println(line);
        } catch (Exception e) {
            try { destFile.createNewFile(); } catch (Exception ignored) {}
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
            if (pw != null) pw.close();
        }
    }
}
