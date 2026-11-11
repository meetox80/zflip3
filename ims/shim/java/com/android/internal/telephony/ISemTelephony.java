package com.android.internal.telephony;

import android.os.IBinder;
import android.os.IInterface;
import android.telephony.CellIdentity;

public interface ISemTelephony extends IInterface {
    CellIdentity getCellLocationBySubId(int subId, String packageName, String featureId);

    int getLteCsCapa(int phoneId);

    int getSupportUacType(int phoneId);

    boolean getSupportedNrca(int phoneId);

    boolean isSupportLteCapaOptionC(int phoneId);

    void sendVolteState(int subId, boolean enabled);

    final class Stub {
        private static final ISemTelephony LOCAL = new ISemTelephony() {
            @Override
            public IBinder asBinder() {
                return null;
            }

            @Override
            public CellIdentity getCellLocationBySubId(int subId, String packageName, String featureId) {
                return null;
            }

            @Override
            public int getLteCsCapa(int phoneId) {
                return 0;
            }

            @Override
            public int getSupportUacType(int phoneId) {
                return 0;
            }

            @Override
            public boolean getSupportedNrca(int phoneId) {
                return false;
            }

            @Override
            public boolean isSupportLteCapaOptionC(int phoneId) {
                return false;
            }

            @Override
            public void sendVolteState(int subId, boolean enabled) {
                android.util.Log.i("ISemTelephony", "sendVolteState sub " + subId + " enabled " + enabled);
            }
        };

        private Stub() {
        }

        public static ISemTelephony asInterface(IBinder binder) {
            return LOCAL;
        }
    }
}
