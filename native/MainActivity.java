package io.github.gnotesamsung7hue.matthewssandbox;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(LanLinkPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
