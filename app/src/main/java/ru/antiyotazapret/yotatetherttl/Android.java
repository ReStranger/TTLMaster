package ru.antiyotazapret.yotatetherttl;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/**
 * Сборник стандартных команд Android.
 *
 * @author Pavel Savinov (swapii@gmail.com)
 */
public class Android {

    private static ShellExecutor executor = new ShellExecutor();
    private static String[] INTERFACE_MASKS = new String[] {"rmnet+", "rev_rmnet+"};
    private static String[] VPN_INTERFACE_MASKS = new String[] {"tun+", "ppp+", "wg+", "tap+"};

    public static void enabledAirplaneMode() throws IOException, InterruptedException {
        executor.executeAsRoot("settings put global airplane_mode_on 1");
        executor.executeAsRoot("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true");
    }

    public static void disableAirplaneMode() throws IOException, InterruptedException {
        executor.executeAsRoot("settings put global airplane_mode_on 0");
        executor.executeAsRoot("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false");
    }

    public static void enabledMobileData() throws IOException, InterruptedException {
        executor.executeAsRoot("svc data enable");
    }

    public static void disableMobileData() throws IOException, InterruptedException {
        executor.executeAsRoot("svc data disable");
    }

    /**
     * Отключение оповещения андроидом оператора о тетеринге.
     */
    public static void disableTetheringNotification() throws IOException, InterruptedException {
        executor.executeAsRoot("settings put global tether_dun_required 0");
    }

    /**
     * Изменение TTL устройства.
     *
     * @param ttl новое значение TTL.
     */
    public static void changeDeviceTtl(int ttl) throws IOException, InterruptedException {
        executor.executeAsRoot(String.format("echo '%d' > /proc/sys/net/ipv4/ip_default_ttl", ttl));
    }

    public static int getDeviceTtl() throws IOException, InterruptedException {
        ShellExecutor.Result result = executor.execute("cat /proc/sys/net/ipv4/ip_default_ttl");
        String output = result.getOutput().trim();
        if (output.isEmpty()) {
            throw new IOException("Unable to read device TTL");
        }

        final int default_ttl;
        try {
            default_ttl = Integer.parseInt(output);
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected TTL value: " + output, e);
        }

        final boolean forced = isTtlForced();
        final boolean workaround = isWorkaroundApplied();

        return workaround ? 63 : (forced ? 64 : default_ttl);
    }

    /**
     * Проверка возможности использования ttl-set
     */
    public static boolean canForceTtl() throws IOException, InterruptedException {
        return executor.executeAsRoot("cat /proc/net/ip_tables_matches | grep -q ttl && echo ok")
                .getOutput().startsWith("ok");
    }

    public static void forceSetTtl() throws  IOException, InterruptedException {
        executor.executeAsRoot("iptables -t mangle -A POSTROUTING -j TTL --ttl-set 64");
    }

    public static void forceSetInputTtl() throws  IOException, InterruptedException {
        executor.executeAsRoot("iptables -t mangle -I PREROUTING -j TTL --ttl-inc 1");
    }

    public static void disableVpnTrafficRouting() throws IOException, InterruptedException {
        executor.executeAsRoot("iptables -t mangle -D PREROUTING -i wlan+ -j CONNMARK --set-mark 65");
        executor.executeAsRoot("iptables -t mangle -D PREROUTING -i ap+ -j CONNMARK --set-mark 65");
        executor.executeAsRoot("iptables -t mangle -D PREROUTING -i rndis+ -j CONNMARK --set-mark 65");
        executor.executeAsRoot("ip rule del fwmark 65 table 165");
        for (String ifaceMask : VPN_INTERFACE_MASKS) {
            executor.executeAsRoot(String.format("ip route del default dev %s table 165", ifaceMask));
        }
        executor.executeAsRoot("ip route flush cache");
    }

    public static void routeVpnTraffic() throws IOException, InterruptedException {
        String vpnInterface = getVpnInterface();
        if (vpnInterface == null) {
            throw new IOException("VPN interface was not detected");
        }

        executor.executeAsRoot("iptables -t mangle -I PREROUTING -i wlan+ -j CONNMARK --set-mark 65");
        executor.executeAsRoot("iptables -t mangle -I PREROUTING -i ap+ -j CONNMARK --set-mark 65");
        executor.executeAsRoot("iptables -t mangle -I PREROUTING -i rndis+ -j CONNMARK --set-mark 65");
        executor.executeAsRoot("ip rule add fwmark 65 table 165");
        executor.executeAsRoot(String.format("ip route add default dev %s table 165", vpnInterface));
        executor.executeAsRoot("ip route flush cache");
    }

    private static String getVpnInterface() throws IOException, InterruptedException {
        ShellExecutor.Result result = executor.executeAsRoot(
                "for p in /sys/class/net/tun* /sys/class/net/ppp* /sys/class/net/wg* /sys/class/net/tap*; do " +
                        "if [ -d \"$p\" ] && [ \"$(cat \"$p/operstate\" 2>/dev/null)\" != \"down\" ]; then basename \"$p\"; exit 0; fi; " +
                        "done; " +
                        "ip route 2>/dev/null | grep -E \" dev (tun|ppp|wg|tap)[0-9]*\" | head -n1 | sed -E 's/.* dev ((tun|ppp|wg|tap)[^ ]*).*/\\1/'"
        );
        String iface = result.getOutput().trim();
        return iface.isEmpty() ? null : iface;
    }

    public static boolean isTtlForced() throws IOException, InterruptedException {
        return executor.executeAsRoot("(iptables -t mangle -L | grep -q 'TTL set to 64' && echo ok)")
                .getOutput().startsWith("ok");
    }

    public static boolean isWorkaroundApplied() throws IOException, InterruptedException {
        return executor.executeAsRoot("(iptables -t filter -S sort_out_interface >/dev/null && echo ok)")
                .getOutput().startsWith("ok");
    }

    public static boolean hasRoot() throws IOException, InterruptedException {
        return executor.executeAsRoot("echo ok")
                .getOutput().startsWith("ok");
    }

    public static boolean hasIptables() throws IOException, InterruptedException {
        return executor.executeAsRoot("iptables -S &>/dev/null && echo ok")
                .getOutput().startsWith("ok");
    }

    public static void disableBlockList() throws IOException, InterruptedException {
        executor.executeAsRoot("iptables -F BLACKLIST; iptables -D INPUT -j BLACKLIST");
    }

    public static void applyBlockList(Set<String> rules) throws IOException, InterruptedException {
        if (rules == null) { //rules not recieved
            return;
        }

        executor.executeAsRoot("iptables -N BLACKLIST; iptables -A INPUT -j BLACKLIST");

        StringBuilder sb = new StringBuilder();
        for (String addr : rules) {
            sb.append(addr).append('\n');
        }
        executor.executeAsRootWithInput("while read s; do iptables -A BLACKLIST -s $s -j DROP; done", sb.toString());
    }

    public static void applyWorkaround() throws IOException, InterruptedException {

        // packets from the device should be 63 too
        Android.changeDeviceTtl(63);

        executor.executeAsRoot("iptables -t filter -F sort_out_interface");
        executor.executeAsRoot("iptables -t filter -N sort_out_interface");

        executor.executeAsRoot(
                "iptables -t filter -N sort_out_interface;" +
                "iptables -t filter -A sort_out_interface -m ttl --ttl-lt 63 -j REJECT;" +
                "iptables -t filter -A sort_out_interface -m ttl --ttl-eq 63 -j RETURN" + // Skip all packets with TTL == 63
                "iptables -t filter -A sort_out_interface -j CONNMARK --set-mark 64"); // All other are marked as 64 (TTL > 63)

        for (String iface : INTERFACE_MASKS) {
            for (String cmd : new String[]{
                    "iptables -t filter -D OUTPUT -o %s -j sort_out_interface",
                    "iptables -t filter -D FORWARD -o %s -j sort_out_interface ",
                    "iptables -t filter -I OUTPUT -o %s -j sort_out_interface",
                    "iptables -t filter -I FORWARD -o %s -j sort_out_interface ",
            }) {
                ShellExecutor.Result r = executor.executeAsRoot(String.format(cmd, iface));
                TtlApplication.Logi(r.getOutput());
            }
        }

        executor.executeAsRoot("ip rule add fwmark 64 table 164");
        executor.executeAsRoot("ip route add default dev lo table 164");
        executor.executeAsRoot("ip route flush cache");
    }


    /**
     * Функция включения тетеринга WiFi
     */
    public static void setWifiTetheringEnabled(Context ctx) {
        WifiManager wifiManager = (WifiManager) ctx.getSystemService(ctx.WIFI_SERVICE);
        wifiManager.setWifiEnabled(false);
        Method[] methods = wifiManager.getClass().getDeclaredMethods();
        for (Method method : methods) {
            if (method.getName().equals("setWifiApEnabled")) {
                try {
                    method.invoke(wifiManager, null, true);
                } catch (Exception e) {
                    TtlApplication.Logi(e.toString());
                }
                break;
            }
        }
    }

}
