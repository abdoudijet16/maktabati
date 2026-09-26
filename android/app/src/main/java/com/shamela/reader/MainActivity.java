package com.shamela.reader;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FolderImporterPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
