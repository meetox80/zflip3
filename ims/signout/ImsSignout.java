import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImsSignout {
    private static final SecureRandom RNG = new SecureRandom();
    private static final List<String[]> UNDO = new ArrayList<>();
    private static final Pattern PARAM = Pattern.compile("([A-Za-z0-9-]+)=(\"([^\"]*)\"|[^,;\\s]+)");

    private static TelephonyManager telephony;
    private static String imsi = "";
    private static String domain;
    private static String impi;
    private static String impu;
    private static String callId;
    private static String fromTag;
    private static DatagramSocket socket;
    private static Socket stream;
    private static Network imsNetwork;
    private static InetAddress local;
    private static InetAddress pcscf;
    private static int portUc;
    private static int portUs;
    private static long spiUc;
    private static long spiUs;
    private static int portPs = 5060;
    private static boolean ipsec;
    private static String securityClient;
    private static String securityVerify;
    private static String realm;
    private static String nonce;
    private static String opaque;
    private static boolean qopAuth;
    private static int nc;
    private static byte[] ck;
    private static byte[] ik;
    private static String checkDigits;
    private static final String FEATURES = ";+g.3gpp.smsip;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";

    public static void main(String[] args) {
        int code = 1;
        checkDigits = args.length > 0 && args[0].matches("[0-9]{6,}") ? args[0] : null;
        try {
            code = run();
        } catch (Throwable t) {
            System.out.println("failed: " + redact(String.valueOf(t)));
        } finally {
            undo();
        }
        System.exit(code);
    }

    private static int run() throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        activityThread.getMethod("initializeMainlineModules").invoke(null);
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);

        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        }
        telephony = context.getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
        imsi = telephony.getSubscriberId();
        String operator = telephony.getSimOperator();
        if (imsi == null || imsi.isEmpty() || operator == null || operator.length() < 5) {
            throw new IOException("no sim identity");
        }
        String mnc = operator.substring(3);
        if (mnc.length() == 2) {
            mnc = "0" + mnc;
        }
        domain = "ims.mnc" + mnc + ".mcc" + operator.substring(0, 3) + ".3gppnetwork.org";
        impi = imsi + "@" + domain;
        impu = "sip:" + impi;
        System.out.println("subscription " + subId + ", home domain " + domain);

        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build())
                .build();
        HandlerThread handlerThread = new HandlerThread("ims");
        handlerThread.start();
        Object lock = new Object();
        Network[] network = new Network[1];
        LinkProperties[] link = new LinkProperties[1];
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                synchronized (lock) {
                    network[0] = n;
                    lock.notifyAll();
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                synchronized (lock) {
                    network[0] = n;
                    link[0] = lp;
                    lock.notifyAll();
                }
            }
        };
        connectivity.requestNetwork(request, callback, new Handler(handlerThread.getLooper()));
        try {
            Network net;
            long deadline = System.currentTimeMillis() + 45000;
            synchronized (lock) {
                while (network[0] == null || link[0] == null || !pickAddresses(link[0])) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        throw new IOException(network[0] == null ? "ims connection did not come up" : "ims connection has no usable p-cscf");
                    }
                    lock.wait(left);
                }
                net = network[0];
            }
            System.out.println("ims connection up, p-cscf " + pcscf.getHostAddress());
            imsNetwork = net;
            openSocket(net);
            return signOut();
        } finally {
            connectivity.unregisterNetworkCallback(callback);
            if (socket != null) {
                socket.close();
            }
            if (stream != null) {
                stream.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean pickAddresses(LinkProperties lp) throws Exception {
        List<InetAddress> servers = (List<InetAddress>) LinkProperties.class.getMethod("getPcscfServers").invoke(lp);
        for (int pass = 0; pass < 2; pass++) {
            for (InetAddress server : servers) {
                boolean v6 = server instanceof Inet6Address;
                if ((pass == 0) != v6) {
                    continue;
                }
                for (LinkAddress address : lp.getLinkAddresses()) {
                    InetAddress candidate = address.getAddress();
                    if ((candidate instanceof Inet6Address) != v6 || candidate.isLinkLocalAddress() || candidate.isLoopbackAddress()) {
                        continue;
                    }
                    pcscf = server;
                    local = candidate;
                    return true;
                }
            }
        }
        return false;
    }

    private static void openSocket(Network net) throws IOException {
        for (int attempt = 0; ; attempt++) {
            portUc = 20000 + 2 * RNG.nextInt(10000);
            DatagramSocket candidate = new DatagramSocket(null);
            try {
                candidate.bind(new InetSocketAddress(local, portUc));
                net.bindSocket(candidate);
                socket = candidate;
                break;
            } catch (IOException e) {
                candidate.close();
                if (attempt > 20) {
                    throw e;
                }
            }
        }
        portUs = portUc + 1;
        spiUc = 0x10000L + RNG.nextInt(0x3fff0000);
        spiUs = spiUc + 1;
        callId = hex(16);
        fromTag = hex(8);
        securityClient = offers(spiUc, spiUs, portUc, portUs);
    }

    private static String offers(long spiC, long spiS, int portC, int portS) {
        StringBuilder offers = new StringBuilder();
        for (String ealg : new String[] {"null", "aes-cbc"}) {
            for (String alg : new String[] {"hmac-sha-1-96", "hmac-md5-96"}) {
                if (offers.length() > 0) {
                    offers.append(", ");
                }
                offers.append("ipsec-3gpp;alg=").append(alg).append(";ealg=").append(ealg)
                        .append(";spi-c=").append(spiC).append(";spi-s=").append(spiS)
                        .append(";port-c=").append(portC).append(";port-s=").append(portS);
            }
        }
        return offers.toString();
    }

    private static String warning(Response r) {
        String w = r.first("warning");
        return w == null ? "" : " (" + w.replaceAll("^[0-9]+ \\S+ ", "") + ")";
    }

    private static int signOut() throws Exception {
        String contact = "<sip:" + uriHost(local) + ":" + portUs + ">" + FEATURES;
        int cseq = 1;
        int expires = 3600;
        String empty = "Digest username=\"" + impi + "\",realm=\"" + domain + "\",uri=\"sip:" + domain + "\",nonce=\"\",response=\"\"";
        Response r = transact(5060, cseq, request(cseq, contact, expires, empty, false));
        System.out.println("register: " + r.line);
        for (int round = 0; round < 3 && r.status == 401; round++) {
            readChallenge(r);
            byte[][] keys = aka();
            if (keys[3] != null) {
                System.out.println("sim asked for a resync");
                cseq++;
                r = transact(5060, cseq, request(cseq, contact, expires, authorization(new byte[0], Base64.encodeToString(keys[3], Base64.NO_WRAP)), false));
                System.out.println("resync: " + r.line);
                continue;
            }
            ck = keys[1];
            ik = keys[2];
            setUpProtection(r);
            cseq++;
            r = transact(portPs, cseq, request(cseq, contact, expires, authorization(keys[0], null), ipsec));
            System.out.println("authenticated register: " + r.line);
            if (r.status == 423 && r.first("min-expires") != null) {
                expires = Integer.parseInt(r.first("min-expires").trim());
                cseq++;
                r = transact(portPs, cseq, request(cseq, contact, expires, authorization(keys[0], null), ipsec));
                System.out.println("authenticated register for " + expires + "s: " + r.line);
            }
            if (r.status == 200) {
                return finish(r, cseq, keys[0]);
            }
            break;
        }
        System.out.println(redact(r.raw));
        return 1;
    }

    private static int finish(Response registered, int cseq, byte[] res) throws Exception {
        List<String> bindings = splitList(registered.all("contact"));
        int others = 0;
        for (String binding : bindings) {
            boolean own = isOwn(binding);
            if (!own) {
                others++;
            }
            System.out.println("binding: " + (own ? "this tool" : "left over from before") + ", expires " + param(binding, "expires"));
        }
        System.out.println(others + " old binding(s) found");
        String registeredClient = securityClient;
        String ownContact = "<sip:" + uriHost(local) + ":" + portUs + ">" + FEATURES + ";expires=0";
        if (checkDigits != null) {
            boolean match = false;
            int numbers = 0;
            for (String uri : splitList(registered.all("p-associated-uri"))) {
                if (uri.contains("tel:") || uri.contains("sip:+")) {
                    numbers++;
                    if (uri.replaceAll("[^0-9]", "").endsWith(checkDigits)) {
                        match = true;
                    }
                }
            }
            System.out.println("phone numbers on this sim: " + numbers + ", your test text went to one of them: " + (match ? "yes" : "no"));
        }
        String[][] attempts = {
                {"own binding, no security offer", ownContact, "none"},
                {"own binding, same security values", ownContact, "same"},
        };
        Response out = null;
        byte[] password = res;
        for (String[] attempt : attempts) {
            securityClient = attempt[2].equals("same") ? registeredClient : null;
            cseq++;
            out = transact(portPs, cseq, request(cseq, attempt[1], 0, authorization(password, null), ipsec));
            System.out.println("sign out " + attempt[0] + ": " + out.line + warning(out));
            if (out.status == 401) {
                readChallenge(out);
                byte[][] keys = aka();
                if (keys[0] == null) {
                    System.out.println("sim asked for a resync during sign out");
                    break;
                }
                password = keys[0];
                if (!splitList(out.all("security-server")).isEmpty()) {
                    System.out.println("network wants new ipsec keys for the sign out");
                }
                cseq++;
                out = transact(portPs, cseq, request(cseq, attempt[1], 0, authorization(password, null), ipsec));
                System.out.println("sign out " + attempt[0] + " after new challenge: " + out.line + warning(out));
            }
            if (out.status == 200) {
                break;
            }
        }
        if (out.status != 200) {
            System.out.println(redact(out.raw));
            return 1;
        }
        List<String> left = splitList(out.all("contact"));
        int active = 0;
        for (String binding : left) {
            String expires = param(binding, "expires");
            if (expires != null && !expires.equals("0")) {
                active++;
            }
        }
        System.out.println("bindings still active: " + active);
        return active == 0 ? 0 : 1;
    }

    private static boolean isOwn(String binding) {
        Matcher m = Pattern.compile("sip:\\[?([0-9A-Fa-f:.]+)\\]?:([0-9]+)").matcher(binding);
        if (!m.find()) {
            return false;
        }
        try {
            return InetAddress.getByName(m.group(1)).equals(local) && Integer.parseInt(m.group(2)) == portUs;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void readChallenge(Response r) throws IOException {
        String header = r.first("www-authenticate");
        if (header == null) {
            throw new IOException("401 without a challenge");
        }
        Map<String, String> p = params(header);
        realm = p.get("realm");
        nonce = p.get("nonce");
        opaque = p.get("opaque");
        String qop = p.get("qop");
        qopAuth = qop != null && Arrays.asList(qop.split("\\s*,\\s*")).contains("auth");
        nc = 0;
        String algorithm = p.get("algorithm");
        if (realm == null || nonce == null || (algorithm != null && !algorithm.equalsIgnoreCase("AKAv1-MD5"))) {
            throw new IOException("unsupported challenge " + algorithm);
        }
    }

    private static byte[][] aka() throws IOException {
        byte[] n = Base64.decode(nonce, Base64.DEFAULT);
        if (n.length < 32) {
            throw new IOException("nonce too short");
        }
        byte[] input = new byte[34];
        input[0] = 16;
        System.arraycopy(n, 0, input, 1, 16);
        input[17] = 16;
        System.arraycopy(n, 16, input, 18, 16);
        String answer = telephony.getIccAuthentication(TelephonyManager.APPTYPE_USIM, TelephonyManager.AUTHTYPE_EAP_AKA, Base64.encodeToString(input, Base64.NO_WRAP));
        if (answer == null) {
            throw new IOException("sim gave no authentication answer");
        }
        byte[] a = Base64.decode(answer, Base64.DEFAULT);
        if ((a[0] & 0xff) == 0xdb) {
            int i = 1;
            byte[][] out = new byte[4][];
            for (int k = 0; k < 3; k++) {
                int len = a[i++] & 0xff;
                out[k] = Arrays.copyOfRange(a, i, i + len);
                i += len;
            }
            return out;
        }
        if ((a[0] & 0xff) == 0xdc) {
            int len = a[1] & 0xff;
            return new byte[][] {null, null, null, Arrays.copyOfRange(a, 2, 2 + len)};
        }
        throw new IOException("unexpected sim answer tag " + (a[0] & 0xff));
    }

    private static void setUpProtection(Response r) throws Exception {
        List<String> offers = splitList(r.all("security-server"));
        if (offers.isEmpty()) {
            ipsec = false;
            portPs = 5060;
            System.out.println("network does not ask for ipsec");
            return;
        }
        Map<String, String> best = null;
        double bestQ = -1;
        for (String offer : offers) {
            Map<String, String> p = params(offer);
            String alg = p.get("alg");
            String ealg = p.containsKey("ealg") ? p.get("ealg") : "null";
            if (!offer.trim().startsWith("ipsec-3gpp") || !("hmac-sha-1-96".equals(alg) || "hmac-md5-96".equals(alg))
                    || !("null".equals(ealg) || "aes-cbc".equals(ealg))) {
                continue;
            }
            double q = p.containsKey("q") ? Double.parseDouble(p.get("q")) : 0;
            if (q > bestQ) {
                bestQ = q;
                best = p;
            }
        }
        if (best == null) {
            throw new IOException("no ipsec offer this tool supports: " + redact(String.join(" | ", offers)));
        }
        securityVerify = String.join(", ", offers);
        String alg = best.get("alg");
        String ealg = best.containsKey("ealg") ? best.get("ealg") : "null";
        long spiPs = Long.parseLong(best.get("spi-s"));
        portPs = Integer.parseInt(best.get("port-s"));
        String auth = alg.equals("hmac-sha-1-96") ? "hmac(sha1)" : "hmac(md5)";
        byte[] authKey = alg.equals("hmac-sha-1-96") ? Arrays.copyOf(ik, 20) : ik;
        String encName = ealg.equals("aes-cbc") ? "cbc(aes)" : "ecb(cipher_null)";
        String encKey = ealg.equals("aes-cbc") ? "0x" + hexOf(ck) : "";
        String ue = plain(local);
        String pc = plain(pcscf);
        String bits = local instanceof Inet6Address ? "/128" : "/32";
        state(ue, pc, spiPs, auth, authKey, encName, encKey);
        state(pc, ue, spiUc, auth, authKey, encName, encKey);
        for (String proto : new String[] {"17", "6"}) {
            policy(ue, pc, bits, proto, portUc, portPs, "out", spiPs);
            policy(pc, ue, bits, proto, portPs, portUc, "in", spiUc);
        }
        ipsec = true;
        System.out.println("ipsec " + alg + "/" + ealg + " set up");
    }

    private static void state(String src, String dst, long spi, String auth, byte[] authKey, String encName, String encKey) throws Exception {
        String spiHex = String.format(Locale.ROOT, "0x%08x", spi);
        ip("xfrm", "state", "add", "src", src, "dst", dst, "proto", "esp", "spi", spiHex, "mode", "transport",
                "auth-trunc", auth, "0x" + hexOf(authKey), "96", "enc", encName, encKey);
        UNDO.add(new String[] {"xfrm", "state", "delete", "src", src, "dst", dst, "proto", "esp", "spi", spiHex});
    }

    private static void policy(String src, String dst, String bits, String proto, int sport, int dport, String dir, long spi) throws Exception {
        String spiHex = String.format(Locale.ROOT, "0x%08x", spi);
        ip("xfrm", "policy", "add", "src", src + bits, "dst", dst + bits, "proto", proto, "sport", String.valueOf(sport),
                "dport", String.valueOf(dport), "dir", dir, "tmpl", "src", src, "dst", dst, "proto", "esp", "spi", spiHex,
                "mode", "transport", "level", "required");
        UNDO.add(new String[] {"xfrm", "policy", "delete", "src", src + bits, "dst", dst + bits, "proto", proto,
                "sport", String.valueOf(sport), "dport", String.valueOf(dport), "dir", dir});
    }

    private static void ip(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "/system/bin/ip";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = read(process.getInputStream());
        if (process.waitFor() != 0) {
            throw new IOException("ip " + args[0] + " " + args[1] + " " + args[2] + " failed: " + output.trim());
        }
    }

    private static synchronized void undo() {
        for (int i = UNDO.size() - 1; i >= 0; i--) {
            try {
                ip(UNDO.get(i));
            } catch (Exception e) {
                System.out.println("cleanup: " + e.getMessage());
            }
        }
        UNDO.clear();
    }

    private static String request(int cseq, String contact, int expires, String authorization, boolean protectedRequest) {
        StringBuilder b = new StringBuilder();
        b.append("REGISTER sip:").append(domain).append(" SIP/2.0\r\n");
        b.append("Via: SIP/2.0/UDP ").append(uriHost(local)).append(':').append(protectedRequest ? portUs : portUc)
                .append(";branch=z9hG4bK").append(hex(10)).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        b.append("From: <").append(impu).append(">;tag=").append(fromTag).append("\r\n");
        b.append("To: <").append(impu).append(">\r\n");
        b.append("Call-ID: ").append(callId).append("\r\n");
        b.append("CSeq: ").append(cseq).append(" REGISTER\r\n");
        b.append("Contact: ").append(contact).append("\r\n");
        b.append("Expires: ").append(expires).append("\r\n");
        b.append("Authorization: ").append(authorization).append("\r\n");
        if (securityClient != null) {
            b.append("Security-Client: ").append(securityClient).append("\r\n");
        }
        if (protectedRequest) {
            b.append("Security-Verify: ").append(securityVerify).append("\r\n");
        }
        b.append("Require: sec-agree\r\n");
        b.append("Proxy-Require: sec-agree\r\n");
        b.append("Supported: path,sec-agree\r\n");
        b.append("P-Access-Network-Info: 3GPP-E-UTRAN-FDD\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        return b.toString();
    }

    private static String authorization(byte[] password, String auts) throws Exception {
        String uri = "sip:" + domain;
        String ha1 = md5(concat((impi + ":" + realm + ":").getBytes(StandardCharsets.UTF_8), password));
        String ha2 = md5(("REGISTER:" + uri).getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder("Digest username=\"").append(impi).append("\",realm=\"").append(realm)
                .append("\",uri=\"").append(uri).append("\",nonce=\"").append(nonce).append("\",algorithm=AKAv1-MD5");
        String response;
        if (qopAuth) {
            nc++;
            String count = String.format(Locale.ROOT, "%08x", nc);
            String cnonce = hex(8);
            response = md5((ha1 + ":" + nonce + ":" + count + ":" + cnonce + ":auth:" + ha2).getBytes(StandardCharsets.UTF_8));
            b.append(",qop=auth,nc=").append(count).append(",cnonce=\"").append(cnonce).append('"');
        } else {
            response = md5((ha1 + ":" + nonce + ":" + ha2).getBytes(StandardCharsets.UTF_8));
        }
        b.append(",response=\"").append(response).append('"');
        if (opaque != null) {
            b.append(",opaque=\"").append(opaque).append('"');
        }
        if (auts != null) {
            b.append(",auts=\"").append(auts).append('"');
        }
        return b.toString();
    }

    private static Response transact(int port, int cseq, String message) throws Exception {
        int size = message.getBytes(StandardCharsets.UTF_8).length;
        try {
            if (size > 1300 || (ipsec && port == portPs)) {
                return transactStream(port, cseq, message.replaceFirst("SIP/2\\.0/UDP ", "SIP/2.0/TCP "));
            }
            return transactDatagram(port, cseq, message);
        } catch (IOException e) {
            System.out.println("request of " + size + " bytes to port " + port + " got no answer");
            counters();
            throw e;
        }
    }

    private static Response transactStream(int port, int cseq, String message) throws IOException {
        if (stream == null) {
            Socket s = new Socket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(local, portUc));
            imsNetwork.bindSocket(s);
            s.connect(new InetSocketAddress(pcscf, port), 10000);
            stream = s;
        }
        stream.setSoTimeout(32000);
        stream.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
        stream.getOutputStream().flush();
        InputStream in = stream.getInputStream();
        while (true) {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int c = in.read();
                if (c < 0) {
                    throw new IOException("p-cscf closed the connection");
                }
                head.write(c);
                matched = (c == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (c == '\r' ? 1 : 0);
            }
            String text = head.toString("UTF-8");
            Response r = Response.parse(text.replaceFirst("^(\r\n)+", ""));
            String length = r == null ? null : r.first("content-length");
            int body = length == null ? 0 : Integer.parseInt(length.trim());
            for (int i = 0; i < body; i++) {
                if (in.read() < 0) {
                    throw new IOException("p-cscf closed the connection");
                }
            }
            if (r == null || r.cseq != cseq || r.status < 200) {
                continue;
            }
            return r;
        }
    }

    private static void counters() {
        try {
            Process process = new ProcessBuilder("/system/bin/ip", "-s", "xfrm", "state").redirectErrorStream(true).start();
            for (String line : read(process.getInputStream()).split("\n")) {
                String t = line.trim();
                if ((t.startsWith("src ") || t.startsWith("proto esp") || t.contains("(packets)") || t.contains("failed"))
                        && !t.matches(".*0x[0-9a-f]{16,}.*")) {
                    System.out.println("  " + t);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.out.println("counters: " + e.getMessage());
        }
    }

    private static Response transactDatagram(int port, int cseq, String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(data, data.length, new InetSocketAddress(pcscf, port));
        byte[] buffer = new byte[65535];
        long deadline = System.currentTimeMillis() + 32000;
        int interval = 1000;
        socket.send(packet);
        long resend = System.currentTimeMillis() + interval;
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                throw new IOException("no answer from the p-cscf on port " + port);
            }
            socket.setSoTimeout((int) Math.max(1, Math.min(resend, deadline) - now));
            try {
                DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                socket.receive(in);
                Response r = Response.parse(new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8));
                if (r == null || r.cseq != cseq) {
                    continue;
                }
                if (r.status < 200) {
                    resend = deadline;
                    continue;
                }
                return r;
            } catch (SocketTimeoutException e) {
                if (System.currentTimeMillis() >= resend && resend < deadline) {
                    socket.send(packet);
                    interval = Math.min(interval * 2, 4000);
                    resend = System.currentTimeMillis() + interval;
                }
            }
        }
    }

    private static final class Response {
        int status;
        int cseq = -1;
        String line;
        String raw;
        final Map<String, List<String>> headers = new LinkedHashMap<>();

        List<String> all(String name) {
            List<String> v = headers.get(name);
            return v == null ? new ArrayList<>() : v;
        }

        String first(String name) {
            List<String> v = all(name);
            return v.isEmpty() ? null : v.get(0);
        }

        static Response parse(String text) {
            int end = text.indexOf("\r\n\r\n");
            String head = end < 0 ? text : text.substring(0, end);
            String[] lines = head.split("\r\n");
            if (lines.length == 0 || !lines[0].startsWith("SIP/2.0 ") || lines[0].length() < 11) {
                return null;
            }
            Response r = new Response();
            r.raw = head;
            r.line = lines[0];
            try {
                r.status = Integer.parseInt(lines[0].substring(8, 11));
            } catch (RuntimeException e) {
                return null;
            }
            String last = null;
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i];
                if ((l.startsWith(" ") || l.startsWith("\t")) && last != null) {
                    List<String> v = r.headers.get(last);
                    v.set(v.size() - 1, v.get(v.size() - 1) + " " + l.trim());
                    continue;
                }
                int colon = l.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = expand(l.substring(0, colon).trim().toLowerCase(Locale.ROOT));
                r.headers.computeIfAbsent(name, k -> new ArrayList<>()).add(l.substring(colon + 1).trim());
                last = name;
            }
            String cseq = r.first("cseq");
            if (cseq != null) {
                try {
                    r.cseq = Integer.parseInt(cseq.trim().split("\\s+")[0]);
                } catch (RuntimeException ignored) {
                    r.cseq = -1;
                }
            }
            return r;
        }

        static String expand(String name) {
            switch (name) {
                case "m": return "contact";
                case "v": return "via";
                case "i": return "call-id";
                case "f": return "from";
                case "t": return "to";
                case "l": return "content-length";
                case "k": return "supported";
                default: return name;
            }
        }
    }

    private static List<String> splitList(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            int angle = 0;
            for (char c : value.toCharArray()) {
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted && c == '<') {
                    angle++;
                } else if (!quoted && c == '>') {
                    angle--;
                }
                if (c == ',' && !quoted && angle == 0) {
                    if (current.toString().trim().length() > 0) {
                        out.add(current.toString().trim());
                    }
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            if (current.toString().trim().length() > 0) {
                out.add(current.toString().trim());
            }
        }
        return out;
    }

    private static Map<String, String> params(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(text);
        while (m.find()) {
            out.put(m.group(1).toLowerCase(Locale.ROOT), m.group(3) != null ? m.group(3) : m.group(2));
        }
        return out;
    }

    private static String param(String binding, String name) {
        int close = binding.lastIndexOf('>');
        return params(close >= 0 ? binding.substring(close) : binding).get(name);
    }

    private static String uriHost(InetAddress address) {
        return address instanceof Inet6Address ? "[" + plain(address) + "]" : plain(address);
    }

    private static String plain(InetAddress address) {
        String text = address.getHostAddress();
        int scope = text.indexOf('%');
        return scope >= 0 ? text.substring(0, scope) : text;
    }

    private static String redact(String text) {
        if (text == null) {
            return "";
        }
        if (imsi != null && !imsi.isEmpty()) {
            text = text.replace(imsi, "[imsi]");
        }
        text = text.replaceAll("(?i)(nonce|cnonce|response|auts)=\"[^\"]*\"", "$1=\"[x]\"");
        return text.replaceAll("\\+?[0-9]{9,}", "[num]");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String md5(byte[] data) throws Exception {
        return hexOf(MessageDigest.getInstance("MD5").digest(data));
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return hexOf(b);
    }

    private static String hexOf(byte[] data) {
        StringBuilder b = new StringBuilder();
        for (byte x : data) {
            b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        }
        return b.toString();
    }
}
