package com.firat.node;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NodeBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { NodeStore.schedule(context); }
}
