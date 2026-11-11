import java.lang.reflect.Method;

public final class SmsReady {
    private static final String INTERFACE = "vendor.samsung.hardware.radio@2.2::ISehRadio";
    private static final int SET_VENDOR_SPECIFIC_CONFIGURATION = 47;
    private static final int FLAG_ONEWAY = 1;

    public static void main(String[] args) throws Exception {
        Class<?> hwBinder = Class.forName("android.os.HwBinder");
        Class<?> hwParcel = Class.forName("android.os.HwParcel");
        Class<?> hwBlob = Class.forName("android.os.HwBlob");
        Class<?> iHwBinder = Class.forName("android.os.IHwBinder");
        Method getService = hwBinder.getMethod("getService", String.class, String.class);
        int sent = 0;
        String[] slots = args.length > 0 ? args : new String[] {"slot1", "slot2"};
        for (String slot : slots) {
            Object binder;
            try {
                binder = getService.invoke(null, INTERFACE, slot);
            } catch (Exception e) {
                System.out.println(slot + ": no radio service");
                continue;
            }
            Object request = hwParcel.getConstructor().newInstance();
            hwParcel.getMethod("writeInterfaceToken", String.class).invoke(request, INTERFACE);
            hwParcel.getMethod("writeInt32", int.class).invoke(request, 1);
            Object vector = hwBlob.getConstructor(int.class).newInstance(16);
            Object items = hwBlob.getConstructor(int.class).newInstance(32);
            hwBlob.getMethod("putString", long.class, String.class).invoke(items, 0L, "FW_READY");
            hwBlob.getMethod("putString", long.class, String.class).invoke(items, 16L, "1");
            hwBlob.getMethod("putInt32", long.class, int.class).invoke(vector, 8L, 1);
            hwBlob.getMethod("putBool", long.class, boolean.class).invoke(vector, 12L, false);
            hwBlob.getMethod("putBlob", long.class, hwBlob).invoke(vector, 0L, items);
            hwParcel.getMethod("writeBuffer", hwBlob).invoke(request, vector);
            Object reply = hwParcel.getConstructor().newInstance();
            try {
                iHwBinder.getMethod("transact", int.class, hwParcel, hwParcel, int.class)
                        .invoke(binder, SET_VENDOR_SPECIFIC_CONFIGURATION, request, reply, FLAG_ONEWAY);
                hwParcel.getMethod("releaseTemporaryStorage").invoke(request);
            } finally {
                hwParcel.getMethod("release").invoke(reply);
            }
            System.out.println(slot + ": ready sent");
            sent++;
        }
        System.exit(sent > 0 ? 0 : 1);
    }
}
