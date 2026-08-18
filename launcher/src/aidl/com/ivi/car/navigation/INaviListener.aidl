// NaviAidlInterface.aidl
package com.ivi.car.navigation;

// Declare any non-default types here with import statements
interface INaviListener {
    void onNaviDataReceived(String data);
}