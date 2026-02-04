package de.kolbasa.apkupdater.tools;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.scottyab.rootbeer.RootBeer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import de.kolbasa.apkupdater.exceptions.InstallationFailedException;
import de.kolbasa.apkupdater.exceptions.InvalidPackageException;
import de.kolbasa.apkupdater.exceptions.RootException;

public class ApkInstaller {

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class SuCommand {
        private final String[] command;
        private final String stdin;

        private SuCommand(String[] command, String stdin) {
            this.command = command;
            this.stdin = stdin;
        }
    }

    private static Uri getUpdate(Context context, File update) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            String fileProvider = context.getPackageName() + ".apkupdater.provider";
            return FileProvider.getUriForFile(context, fileProvider, update);
        } else {
            File externalPath = new File(context.getExternalCacheDir(), update.getName());
            FileTools.copy(update, externalPath);
            return Uri.fromFile(externalPath);
        }
    }

    public static void install(Context context, File update) throws IOException {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setData(getUpdate(context, update));
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(getUpdate(context, update), "application/vnd.android.package-archive");
        }
        if (WindowStatus.isWindowed(context)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        context.startActivity(intent);
    }

    public static boolean isDeviceRooted(Context context) {
        RootBeer rootBeer = new RootBeer(context);
        return (rootBeer.checkSuExists() && (rootBeer.checkForRWPaths() || rootBeer.checkForRootNative()));
    }

    /**
     * https://stackoverflow.com/a/39420232
     */
    public static boolean requestRootAccess() throws RootException {
        String command = "id";
        String stdin = command + "\nexit\n";
        SuCommand[] commands = new SuCommand[]{
                new SuCommand(new String[]{"su", "0", "sh", "-c", command}, null),
                new SuCommand(new String[]{"su", "0", command}, null),
                new SuCommand(new String[]{"su", "-c", command}, null),
                new SuCommand(new String[]{"su", "0"}, stdin),
                new SuCommand(new String[]{"su"}, stdin)
        };

        String lastOutput = "";
        Exception lastException = null;

        for (SuCommand suCommand : commands) {
            try {
                CommandResult result = execCommand(suCommand.command, suCommand.stdin);
                String output = result.output == null ? "" : result.output;
                lastOutput = output;
                if (output.toLowerCase(Locale.US).contains("uid=0")) {
                    return true;
                }
            } catch (Exception e) {
                lastException = e;
                lastOutput = e.getMessage();
            }
        }

        if (lastException != null) {
            throw new RootException(new Exception("su check failed (missing -c or uid support?): " + lastOutput, lastException));
        }

        return false;
    }

    public static void rootInstall(Context context, File update) throws IOException,
            PackageManager.NameNotFoundException, InvalidPackageException, RootException {
        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        String mainActivity = launchIntent.getComponent().getClassName();

        // -r Reinstall if needed
        // -d Downgrade if needed
        String command = "pm install -r -d '" + update.getCanonicalPath() + "'";

        if (AppData.getPackageInfo(context, update).getPackageName().equals(packageName)) {
            // Restart app if same package
            command += " && am start -n " + packageName + "/" + mainActivity;
        }

        // Try multiple su variants, including stdin fallback for su builds without -c support.
        String stdin = command + "\nexit\n";
        SuCommand[] suCommands = new SuCommand[]{
                new SuCommand(new String[]{"su", "0", "sh", "-c", command}, null),
                new SuCommand(new String[]{"su", "0", command}, null),
                new SuCommand(new String[]{"su", "0"}, stdin),
                new SuCommand(new String[]{"su", "-c", "sh", "-c", command}, null),
                new SuCommand(new String[]{"su", "-c", command}, null),
                new SuCommand(new String[]{"su"}, stdin)
        };

        String lastOutput = "";
        Exception lastException = null;

        for (SuCommand suCommand : suCommands) {
            try {
                CommandResult result = execCommand(suCommand.command, suCommand.stdin);
                lastOutput = result.output == null ? "" : result.output;

                if (!isRootInstallSuccess(result)) {
                    // pm/am reported an error; try next su variant first, then bubble up.
                    continue;
                }

                // Success reached; nothing else to do.
                return;
            } catch (Exception e) {
                lastException = e;
                lastOutput = e.getMessage();
            }
        }

        // If we get here, all su variants failed.
        if (lastException != null) {
            throw new RootException(new Exception("Root command failed: " + lastOutput, lastException));
        } else if (!"".equals(lastOutput)) {
            throw new RootException(new Exception(lastOutput));
        } else {
            throw new RootException(new Exception("Root command failed"));
        }
    }

    public static boolean isDeviceOwner(Context context) {
        DevicePolicyManager mDPM = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return mDPM.isDeviceOwnerApp(context.getPackageName());
    }

    public static void ownerInstall(Context context, File update) throws IOException {
        if (!isDeviceOwner(context)) {
            throw new SecurityException("App is not device owner");
        }

        InputStream in = context.getContentResolver().openInputStream(getUpdate(context, update));

        PackageManager pm = context.getPackageManager();
        PackageInstaller pi = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);

        int sessionId = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(sessionId);
        OutputStream out = s.openWrite(update.getName(), 0, -1);
        byte[] buffer = new byte[65536];
        int chunk;
        while ((chunk = in.read(buffer)) != -1) {
            out.write(buffer, 0, chunk);
        }
        s.fsync(out);
        in.close();
        out.close();

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, new Intent(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        s.commit(pendingIntent.getIntentSender());
        s.close();
    }

    private static boolean isRootInstallSuccess(CommandResult result) {
        String output = result.output == null ? "" : result.output.trim();
        if (output.isEmpty()) {
            return result.exitCode == 0;
        }

        String normalized = output.toLowerCase(Locale.US);
        if (normalized.contains("success")) {
            return true;
        }
        if (normalized.contains("failure") || normalized.contains("error")) {
            return false;
        }

        return result.exitCode == 0;
    }

    private static CommandResult execCommand(String[] command, String stdin)
            throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(command);
        if (stdin != null) {
            OutputStream out = process.getOutputStream();
            out.write(stdin.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        }

        String stdOut = readStream(process.getInputStream());
        String stdErr = readStream(process.getErrorStream());
        int exitCode = process.waitFor();
        process.destroy();

        String output;
        if (!stdOut.isEmpty() && !stdErr.isEmpty()) {
            output = stdOut + "\n" + stdErr;
        } else if (!stdOut.isEmpty()) {
            output = stdOut;
        } else {
            output = stdErr;
        }

        return new CommandResult(exitCode, output);
    }

    private static String readStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

}
