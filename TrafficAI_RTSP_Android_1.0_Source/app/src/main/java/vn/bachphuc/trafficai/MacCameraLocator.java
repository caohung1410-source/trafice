package vn.bachphuc.trafficai;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tìm IP hiện tại của camera đã ghim MAC. Lượt đầu thử cache láng giềng và IP cũ,
 * sau đó chỉ quét tối đa một mạng /24 hiện hành để tránh tải mạng và hao pin.
 */
public final class MacCameraLocator {
    private static final int CONNECT_TIMEOUT_MS = 260;
    private static final int MAX_SCAN_HOSTS = 254;

    public Result locate(String requestedMac, String lastHost, int rtspPort) {
        String target = MacAddressPolicy.normalize(requestedMac);
        if (!MacAddressPolicy.isValidDeviceMac(target)) {
            return Result.failure("MAC chưa đúng định dạng AA:BB:CC:DD:EE:FF");
        }
        int port = rtspPort > 0 && rtspPort <= 65535 ? rtspPort : 554;

        if (isIpv4(lastHost) && probe(lastHost, port)) {
            // Android 10+ thường chặn /proc/net/arp và `ip neigh` với ứng dụng thường.
            // IP đã được lưu cùng MAC và cổng RTSP đang trả lời là đường kết nối nhanh,
            // không buộc người dùng cấp quyền Wi-Fi không liên quan đến socket LAN.
            return Result.success(lastHost, true);
        }

        String cached = hostForMac(target);
        if (cached != null && probe(cached, port)) return Result.success(cached, false);

        Subnet subnet = activeSubnet();
        if (subnet == null) {
            return Result.failure("Không xác định được mạng Wi-Fi/LAN hiện tại");
        }
        warmNeighborTable(subnet, port);
        String discovered = hostForMac(target);
        if (discovered == null) {
            return Result.failure("Không thấy MAC " + target
                    + " trong mạng " + subnet.display
                    + ". Camera và điện thoại phải cùng Wi-Fi; một số Android chặn đọc ARP.");
        }
        if (!probe(discovered, port)) {
            return Result.failure("Đã thấy đúng MAC tại " + discovered
                    + " nhưng cổng RTSP " + port + " chưa mở");
        }
        return Result.success(discovered, false);
    }

    private void warmNeighborTable(Subnet subnet, int port) {
        ExecutorService pool = Executors.newFixedThreadPool(36);
        int submitted = 0;
        for (int host = 1; host < subnet.hostCount - 1 && submitted < MAX_SCAN_HOSTS; host++) {
            int address = subnet.networkAddress + host;
            String ip = intToIpv4(address);
            if (ip.equals(subnet.localAddress)) continue;
            submitted++;
            pool.execute(() -> probe(ip, port));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean probe(String host, int port) {
        if (!isIpv4(host)) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String hostForMac(String target) {
        for (Map.Entry<String, String> entry : neighborTable().entrySet()) {
            if (MacAddressPolicy.matches(target, entry.getValue())) return entry.getKey();
        }
        return null;
    }

    private Map<String, String> neighborTable() {
        Map<String, String> result = new LinkedHashMap<>();
        readProcArp(result);
        readIpNeighbor(result);
        return result;
    }

    private void readProcArp(Map<String, String> output) {
        File table = new File("/proc/net/arp");
        if (!table.canRead()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(table))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4 && isIpv4(parts[0])
                        && MacAddressPolicy.isValidDeviceMac(parts[3])) {
                    output.put(parts[0], MacAddressPolicy.normalize(parts[3]));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void readIpNeighbor(Map<String, String> output) {
        List<String> commands = new ArrayList<>();
        commands.add("/system/bin/ip");
        commands.add("ip");
        for (String command : commands) {
            Process process = null;
            try {
                process = new ProcessBuilder(command, "neigh", "show")
                        .redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+");
                        String ip = parts.length > 0 ? parts[0] : "";
                        for (int index = 1; index + 1 < parts.length; index++) {
                            if ("lladdr".equals(parts[index]) && isIpv4(ip)
                                    && MacAddressPolicy.isValidDeviceMac(parts[index + 1])) {
                                output.put(ip, MacAddressPolicy.normalize(parts[index + 1]));
                                break;
                            }
                        }
                    }
                }
                process.waitFor(500, TimeUnit.MILLISECONDS);
                if (!output.isEmpty()) return;
            } catch (Exception ignored) {
            } finally {
                if (process != null) process.destroy();
            }
        }
    }

    private Subnet activeSubnet() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> all = interfaces == null
                    ? Collections.emptyList() : Collections.list(interfaces);
            for (NetworkInterface network : all) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName().toLowerCase(Locale.US);
                if (!(name.startsWith("wlan") || name.startsWith("eth")
                        || name.startsWith("ap") || name.startsWith("en"))) continue;
                for (InterfaceAddress item : network.getInterfaceAddresses()) {
                    InetAddress address = item.getAddress();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String local = address.getHostAddress();
                    if (!isIpv4(local) || !address.isSiteLocalAddress()) continue;
                    int prefix = Math.max(24, Math.min(30, item.getNetworkPrefixLength()));
                    int localInt = ipv4ToInt(local);
                    int mask = prefix == 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
                    int networkAddress = localInt & mask;
                    int hostCount = 1 << (32 - prefix);
                    return new Subnet(local, networkAddress, hostCount,
                            intToIpv4(networkAddress) + "/" + prefix);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isIpv4(String value) {
        if (value == null) return false;
        String[] parts = value.trim().split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) return false;
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int ipv4ToInt(String value) {
        String[] parts = value.split("\\.");
        int result = 0;
        for (String part : parts) result = (result << 8) | Integer.parseInt(part);
        return result;
    }

    private static String intToIpv4(int value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "."
                + ((value >>> 8) & 255) + "." + (value & 255);
    }

    private static final class Subnet {
        final String localAddress;
        final int networkAddress;
        final int hostCount;
        final String display;

        Subnet(String localAddress, int networkAddress, int hostCount, String display) {
            this.localAddress = localAddress;
            this.networkAddress = networkAddress;
            this.hostCount = hostCount;
            this.display = display;
        }
    }

    public static final class Result {
        public final String host;
        public final String message;
        public final boolean usedLastHost;

        private Result(String host, String message, boolean usedLastHost) {
            this.host = host;
            this.message = message;
            this.usedLastHost = usedLastHost;
        }

        public boolean found() {
            return host != null && !host.isEmpty();
        }

        static Result success(String host, boolean usedLastHost) {
            return new Result(host, usedLastHost
                    ? "IP đã lưu đang hoạt động • khóa MAC tại " + host
                    : "Đã tìm lại MAC tại IP " + host, usedLastHost);
        }

        static Result failure(String message) {
            return new Result("", message, false);
        }
    }
}
