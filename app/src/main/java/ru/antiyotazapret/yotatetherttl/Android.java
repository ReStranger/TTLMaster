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
    private static String[] TETHER_INTERFACE_MASKS = new String[] {"wlan+", "ap+", "rndis+", "usb+", "bt-pan"};
    private static String[] IPTABLES_CANDIDATES = new String[] {"iptables", "iptables-legacy", "iptables-nft"};
    private static final int VPN_TETHER_TABLE = 61;

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
        String output = readDeviceTtlValue();
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
        String iptablesCommand = getIptablesCommand();
        if (iptablesCommand == null) {
            return false;
        }

        String addRule = String.format("%s -t mangle -A POSTROUTING -j TTL --ttl-set 64 >/dev/null 2>&1", iptablesCommand);
        ShellExecutor.Result addResult = executor.executeAsRoot(addRule);
        if (addResult.getExitCode() != 0) {
            return false;
        }

        executor.executeAsRoot(String.format("%s -t mangle -D POSTROUTING -j TTL --ttl-set 64 >/dev/null 2>&1", iptablesCommand));
        return true;
    }

    public static void forceSetTtl() throws  IOException, InterruptedException {
        executor.executeAsRoot(String.format("%s -t mangle -A POSTROUTING -j TTL --ttl-set 64", getRequiredIptablesCommand()));
    }

    public static void forceSetInputTtl() throws  IOException, InterruptedException {
        executor.executeAsRoot(String.format("%s -t mangle -I PREROUTING -j TTL --ttl-inc 1", getRequiredIptablesCommand()));
    }

    public static void disableVpnTrafficRouting() throws IOException, InterruptedException {
        String iptablesCommand = getRequiredIptablesCommand();
        String vpnInterface = getVpnInterface();
        TetherInterface tetherInterface = getTetherInterface();

        if (vpnInterface != null) {
            executor.executeAsRoot(String.format("%s -t nat -D POSTROUTING -o %s -j MASQUERADE >/dev/null 2>&1", iptablesCommand, vpnInterface));
        }

        executor.executeAsRoot(String.format("%s -t filter -D FORWARD -j ACCEPT >/dev/null 2>&1", iptablesCommand));

        if (tetherInterface != null) {
            executor.executeAsRoot(String.format("ip rule del from %s lookup %d >/dev/null 2>&1", tetherInterface.subnet, VPN_TETHER_TABLE));
            executor.executeAsRoot(String.format("ip route del %s dev %s scope link table %d >/dev/null 2>&1", tetherInterface.subnet, tetherInterface.name, VPN_TETHER_TABLE));
            executor.executeAsRoot(String.format("ip route del broadcast 255.255.255.255 dev %s scope link table %d >/dev/null 2>&1", tetherInterface.name, VPN_TETHER_TABLE));
        }

        if (vpnInterface != null) {
            executor.executeAsRoot(String.format("ip route del default dev %s scope link table %d >/dev/null 2>&1", vpnInterface, VPN_TETHER_TABLE));
            executor.executeAsRoot(String.format("ip route del default dev %s table %d >/dev/null 2>&1", vpnInterface, VPN_TETHER_TABLE));
        }

        executor.executeAsRoot(String.format("ip route flush table %d >/dev/null 2>&1", VPN_TETHER_TABLE));
        executor.executeAsRoot("ip route flush cache");
    }

    public static void routeVpnTraffic() throws IOException, InterruptedException {
        String iptablesCommand = getRequiredIptablesCommand();
        String vpnInterface = getVpnInterface();
        TetherInterface tetherInterface = getTetherInterface();
        if (vpnInterface == null) {
            throw new IOException("VPN interface was not detected");
        }
        if (tetherInterface == null) {
            throw new IOException("Tethering interface was not detected");
        }

        executor.executeAsRoot(String.format("%s -t filter -I FORWARD -j ACCEPT", iptablesCommand));
        executor.executeAsRoot(String.format("%s -t nat -I POSTROUTING -o %s -j MASQUERADE", iptablesCommand, vpnInterface));
        executor.executeAsRoot(String.format("ip rule add from %s lookup %d", tetherInterface.subnet, VPN_TETHER_TABLE));
        executor.executeAsRoot(String.format("ip route add default dev %s scope link table %d", vpnInterface, VPN_TETHER_TABLE));
        executor.executeAsRoot(String.format("ip route add %s dev %s scope link table %d", tetherInterface.subnet, tetherInterface.name, VPN_TETHER_TABLE));
        executor.executeAsRoot(String.format("ip route add broadcast 255.255.255.255 dev %s scope link table %d", tetherInterface.name, VPN_TETHER_TABLE));
        executor.executeAsRoot("ip route flush cache");
    }

    public static String getVpnRoutingStatus() throws IOException, InterruptedException {
        String vpnInterface = getVpnInterface();
        TetherInterface tetherInterface = getTetherInterface();

        StringBuilder sb = new StringBuilder();
        sb.append("vpn=").append(vpnInterface == null ? "-" : vpnInterface);
        sb.append(", tether=").append(tetherInterface == null ? "-" : tetherInterface.name);
        sb.append(", subnet=").append(tetherInterface == null ? "-" : tetherInterface.subnet);
        return sb.toString();
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

    private static TetherInterface getTetherInterface() throws IOException, InterruptedException {
        for (String ifaceMask : TETHER_INTERFACE_MASKS) {
            ShellExecutor.Result ifaceResult = executor.executeAsRoot(String.format(
                    "for p in /sys/class/net/%s; do " +
                            "if [ -d \"$p\" ] && [ \"$(cat \"$p/operstate\" 2>/dev/null)\" != \"down\" ]; then basename \"$p\"; exit 0; fi; " +
                            "done",
                    ifaceMask
            ));
            String iface = ifaceResult.getOutput().trim();
            if (iface.isEmpty()) {
                continue;
            }

            String subnet = getIpv4Subnet(iface);
            if (!subnet.isEmpty()) {
                return new TetherInterface(iface, subnet);
            }
        }

        ShellExecutor.Result fallback = executor.executeAsRoot(
                "ip -4 addr show 2>/dev/null | awk '" +
                        "/^[0-9]+: / { iface=$2; sub(\":\", \"\", iface) } " +
                        "/ inet 192\\.168\\./ { print iface \" \" $2; exit }'"
        );
        String[] parts = fallback.getOutput().trim().split("\\s+");
        if (parts.length == 2) {
            return new TetherInterface(parts[0], parts[1]);
        }

        return null;
    }

    private static String getIpv4Subnet(String iface) throws IOException, InterruptedException {
        ShellExecutor.Result result = executor.executeAsRoot(String.format(
                "ip -4 addr show dev %s 2>/dev/null | awk '/ inet / { print $2; exit }'",
                iface
        ));
        return result.getOutput().trim();
    }

    private static class TetherInterface {
        final String name;
        final String subnet;

        TetherInterface(String name, String subnet) {
            this.name = name;
            this.subnet = subnet;
        }
    }

    public static boolean isTtlForced() throws IOException, InterruptedException {
        String iptablesCommand = getIptablesCommand();
        if (iptablesCommand == null) {
            return false;
        }

        return executor.executeAsRoot(String.format("(%s -t mangle -L 2>/dev/null | grep -q 'TTL set to 64' && echo ok)", iptablesCommand))
                .getOutput().startsWith("ok");
    }

    public static boolean isWorkaroundApplied() throws IOException, InterruptedException {
        String iptablesCommand = getIptablesCommand();
        if (iptablesCommand == null) {
            return false;
        }

        return executor.executeAsRoot(String.format("(%s -t filter -S sort_out_interface >/dev/null 2>&1 && echo ok)", iptablesCommand))
                .getOutput().startsWith("ok");
    }

    public static boolean hasRoot() throws IOException, InterruptedException {
        return executor.executeAsRoot("echo ok")
                .getOutput().startsWith("ok");
    }

    public static boolean hasIptables() throws IOException, InterruptedException {
        return getIptablesCommand() != null;
    }

    private static String readDeviceTtlValue() throws IOException, InterruptedException {
        for (String command : new String[] {
                "sysctl -n net.ipv4.ip_default_ttl 2>/dev/null",
                "cat /proc/sys/net/ipv4/ip_default_ttl 2>/dev/null"
        }) {
            String output = executor.execute(command).getOutput().trim();
            if (!output.isEmpty()) {
                return output;
            }
        }

        for (String command : new String[] {
                "sysctl -n net.ipv4.ip_default_ttl 2>/dev/null",
                "cat /proc/sys/net/ipv4/ip_default_ttl 2>/dev/null"
        }) {
            String output = executor.executeAsRoot(command).getOutput().trim();
            if (!output.isEmpty()) {
                return output;
            }
        }

        return "";
    }

    private static String getIptablesCommand() throws IOException, InterruptedException {
        for (String candidate : IPTABLES_CANDIDATES) {
            ShellExecutor.Result result = executor.executeAsRoot(String.format("command -v %s >/dev/null 2>&1 && echo ok", candidate));
            if (result.getOutput().startsWith("ok")) {
                return candidate;
            }
        }

        return null;
    }

    private static String getRequiredIptablesCommand() throws IOException, InterruptedException {
        String iptablesCommand = getIptablesCommand();
        if (iptablesCommand == null) {
            throw new IOException("iptables command was not detected");
        }

        return iptablesCommand;
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
