package android.sec.enterprise;

public class PhoneRestrictionPolicy {
    public boolean canIncomingSms(String address) {
        return true;
    }

    public boolean canOutgoingSms(String address) {
        return true;
    }

    public boolean isSmsPatternCheckRequired() {
        return false;
    }
}
