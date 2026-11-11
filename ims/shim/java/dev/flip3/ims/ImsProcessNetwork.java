package dev.flip3.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ImsProcessNetwork extends ConnectivityManager.NetworkCallback {
    private static final String TAG = "Flip3ImsNetwork";
    private static final int ADDRESS_FD = 987;
    private static final int SCOPE_GLOBAL = 0x00;
    private static final int SCOPE_LINK = 0x20;
    private static final int FLAG_PERMANENT = 0x80;

    private final ConnectivityManager connectivity;
    private final File addresses;

    private ImsProcessNetwork(ConnectivityManager connectivity, File addresses) {
        this.connectivity = connectivity;
        this.addresses = addresses;
    }

    static void start(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        File addresses = new File(context.getFilesDir(), "if_inet6");
        try {
            FileDescriptor opened = Os.open(addresses.getPath(), OsConstants.O_RDONLY | OsConstants.O_CREAT, 0600);
            Os.dup2(opened, ADDRESS_FD);
            Os.close(opened);
        } catch (ErrnoException failed) {
            Log.e(TAG, "could not publish addresses", failed);
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .build();
        connectivity.registerNetworkCallback(request, new ImsProcessNetwork(connectivity, addresses));
    }

    @Override
    public void onAvailable(Network network) {
        publish(connectivity.getLinkProperties(network));
        boolean bound = connectivity.bindProcessToNetwork(network);
        Log.i(TAG, "bind process to " + network + ": " + bound);
    }

    @Override
    public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
        publish(properties);
    }

    @Override
    public void onLost(Network network) {
        if (network.equals(connectivity.getBoundNetworkForProcess())) {
            connectivity.bindProcessToNetwork(null);
            publish(null);
            Log.i(TAG, "released " + network);
        }
    }

    private void publish(LinkProperties properties) {
        StringBuilder lines = new StringBuilder();
        if (properties != null && properties.getInterfaceName() != null) {
            String name = properties.getInterfaceName();
            try {
                NetworkInterface device = NetworkInterface.getByName(name);
                if (device != null) {
                    for (LinkAddress address : properties.getLinkAddresses()) {
                        InetAddress ip = address.getAddress();
                        if (ip instanceof Inet6Address) {
                            lines.append(line(ip, device.getIndex(), address.getPrefixLength(), name));
                        }
                    }
                }
            } catch (IOException failed) {
                Log.e(TAG, "could not read " + name, failed);
            }
        }
        try (FileOutputStream output = new FileOutputStream(addresses)) {
            output.write(lines.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (IOException failed) {
            Log.e(TAG, "could not write addresses", failed);
        }
    }

    private static String line(InetAddress ip, int index, int prefix, String name) {
        StringBuilder hex = new StringBuilder();
        for (byte part : ip.getAddress()) {
            hex.append(String.format(Locale.US, "%02x", part & 0xFF));
        }
        int scope = ip.isLinkLocalAddress() ? SCOPE_LINK : SCOPE_GLOBAL;
        return String.format(Locale.US, "%s %02x %02x %02x %02x %8s\n", hex, index, prefix, scope, FLAG_PERMANENT, name);
    }
}
