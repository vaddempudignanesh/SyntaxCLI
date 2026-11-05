package vaddempudi.gnanesh.syntaxcli.tuils.libsuperuser;

import android.content.Context;

import java.io.File;
import java.util.regex.Pattern;

import vaddempudi.gnanesh.syntaxcli.managers.TerminalManager;
import vaddempudi.gnanesh.syntaxcli.managers.xml.XMLPrefsManager;
import vaddempudi.gnanesh.syntaxcli.managers.xml.options.Behavior;
import vaddempudi.gnanesh.syntaxcli.tuils.BusyBoxInstaller;
import vaddempudi.gnanesh.syntaxcli.tuils.Tuils;

public class ShellHolder {

    private Context context;

    public ShellHolder(Context context) {
        this.context = context;
    }

    Pattern p = Pattern.compile("^\\n");

    public Shell.Interactive build() {
        String filesDir = context.getFilesDir().getAbsolutePath();
        String cacheDir = context.getCacheDir().getAbsolutePath();
        String path = filesDir + ":/system/bin:/system/xbin:/vendor/bin:/sbin";
        String home = "/sdcard/t-ui";
        try {
            File hp = XMLPrefsManager.get(File.class, Behavior.home_path);
            if (hp != null) home = hp.getAbsolutePath();
        } catch (Throwable ignored) { }

        Shell.Interactive interactive = new Shell.Builder()
                .addEnvironment("PATH",   path)
                .addEnvironment("HOME",   home)
                .addEnvironment("TMPDIR", cacheDir)
                .addEnvironment("LANG",   "C")
                .addEnvironment("USER",   "shell")
                .setOnSTDOUTLineListener(line -> {
                    line = p.matcher(line).replaceAll(Tuils.EMPTYSTRING);
                    Tuils.sendOutput(context, line, TerminalManager.CATEGORY_OUTPUT);
                })
                .setOnSTDERRLineListener(line -> {
                    line = p.matcher(line).replaceAll(Tuils.EMPTYSTRING);
                    Tuils.sendOutput(context, line, TerminalManager.CATEGORY_OUTPUT);
                })
                .open();

        setupBusyBox(interactive);
        interactive.addCommand("cd '" + home + "'");
        return interactive;
    }
    private void setupBusyBox(Shell.Interactive interactive) {
        if (BusyBoxInstaller.isInstalled(context)) {
            String bbPath = BusyBoxInstaller.getBusyboxPath(context);
            if (bbPath != null) {
                // 1. Alias 'busybox' to the downloaded path
                interactive.addCommand("alias busybox='" + bbPath + "'");

                // 2. Add common applet aliases so they use BusyBox instead of Toybox
                String[] commonApplets = {
                        "bc", "dc", "cal", "sh", "ash", "ash", "httpd", "telnetd",
                        "diff", "patch", "uniq", "cmp", "comm", "tee", "split",
                        "readlink", "realpath", "mktemp", "stat", "file", "strings",
                        "yes", "printf", "seq", "basename", "dirname", "tr", "od", "xxd",
                        "base64", "md5sum", "sha1sum", "sha256sum", "sha512sum",
                        "hexdump", "dos2unix", "unix2dos", "install", "ln",
                        "mke2fs", "mkfs.ext2", "mkfs.vfat", "mkswap", "swapon", "swapoff",
                        "chroot", "chpst", "crontab", "crond", "devmem", "dd", "eject",
                        "envdir", "envuidgid", "flock", "fuser", "hexedit", "hostid",
                        "ifenslave", "ionice", "lsof", "lzop", "mdev", "microcom",
                        "mkdosfs", "mkfifo", "mknod", "mkpasswd", "mkswap", "mpstat",
                        "nl", "nohup", "nsenter", "partprobe", "passwd", "paste", "pgrep",
                        "pivot_root", "pkill", "pmap", "poweroff", "pr", "ps", "pstree",
                        "pwdx", "raidautorun", "rdev", "readahead", "readlink", "reboot",
                        "renice", "reset", "resize", "rev", "rfkill", "rmmod", "run-init",
                        "run-parts", "runlevel", "runsv", "runsvdir", "rx", "script",
                        "scriptreplay", "sed", "seq", "setfont", "setkeycodes", "setlogcons",
                        "setsid", "setuidgid", "sh", "sha1sum", "sha256sum", "sha3sum",
                        "sha512sum", "showkey", "slattach", "sleep", "softlimit", "sort",
                        "split", "ssl_client", "start-stop-daemon", "stat", "strings", "stty",
                        "su", "sulogin", "sum", "sv", "svc", "svlogd", "swapoff", "swapon",
                        "switch_root", "sync", "sysctl", "syslogd", "tac", "tail", "tar",
                        "taskset", "tcpsvd", "tee", "telnet", "telnetd", "test", "tftp",
                        "tftpd", "time", "timeout", "top", "touch", "tr", "traceroute",
                        "traceroute6", "true", "truncate", "ts", "tsort", "tty", "ttysize",
                        "tunctl", "tune2fs", "ubiattach", "ubidetach", "ubimkvol", "ubirename",
                        "ubirmvol", "ubirsvol", "ubiuupdate", "ubirsvol", "ubirsvol",
                        "udhcpc", "udhcpc6", "udhcpd", "udpsvd", "uevent", "umount",
                        "uname", "uncompress", "unexpand", "uniq", "unix2dos", "unlink",
                        "unlzma", "unshare", "unxz", "unzip", "uptime", "users", "usleep",
                        "uudecode", "uuencode", "vconfig", "vi", "vlock", "volname", "w",
                        "wall", "watch", "watchdog", "wc", "wget", "which", "who", "whoami",
                        "whois", "xargs", "xxd", "xz", "xzcat", "yes", "zcat", "zcip"
                };

                for (String applet : commonApplets) {
                    interactive.addCommand("alias " + applet + "='" + bbPath + " " + applet + "'");
                }
            }
        }
    }
}
