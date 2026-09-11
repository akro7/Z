package top.niunaijun.blackbox.utils.compat;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstalledModule;
import top.niunaijun.blackbox.utils.CloseUtils;
import top.niunaijun.blackbox.utils.ShellUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class XposedParserCompat {
    public static InstalledModule parseModule(ApplicationInfo applicationInfo) {
        try {
            PackageManager packageManager = BlackBoxCore.getPackageManager();
            InstalledModule installedModule = new InstalledModule();
            installedModule.packageName = applicationInfo.packageName;
            installedModule.enable = false;
            if (applicationInfo.metaData != null) {
                installedModule.desc = applicationInfo.metaData.getString("xposeddescription");
            }
            installedModule.name = applicationInfo.loadLabel(packageManager).toString();
            installedModule.main = readMain(applicationInfo.sourceDir);
            return installedModule;
        } catch (Throwable unused) {
            return null;
        }
    }

    public static boolean isXPModule(String str) {
        return readMain(str) != null;
    }

    private static String readMain(String str) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(new File(str));
            ZipEntry entry = zipFile.getEntry("assets/xposed_init");
            if (entry == null) {
                return null;
            }
            return getInputStreamContent(zipFile.getInputStream(entry)).trim();
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        } finally {
            CloseUtils.close(zipFile);
        }
    }

    private static String getInputStreamContent(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader bufferedReader = null;
        try {
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (!line.startsWith("#")) {
                    sb.append(line).append(ShellUtils.COMMAND_LINE_END);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            CloseUtils.close(bufferedReader);
            CloseUtils.close(inputStream);
        }
        return sb.toString();
    }
}
