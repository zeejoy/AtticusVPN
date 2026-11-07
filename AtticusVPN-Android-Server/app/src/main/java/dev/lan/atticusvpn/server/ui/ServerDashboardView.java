/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.lan.atticusvpn.server.R;

/** Read-only home summary. Receives public counters; never owns keys or service commands. */
final class ServerDashboardView extends LinearLayout {
    private final TextView protocol,status,deviceLabel,devices,receive,transmit,detail;
    private final View dot;
    private int dotColor=Integer.MIN_VALUE;
    ServerDashboardView(Context context) {
        super(context);setOrientation(VERTICAL);setPadding(dp(20),dp(19),dp(20),dp(12));
        GradientDrawable background=new GradientDrawable();background.setColor(context.getColor(R.color.atticus_card));
        background.setCornerRadius(dp(22));background.setStroke(dp(1),context.getColor(R.color.home_card_border));
        setBackground(background);setElevation(dp(2));
        LinearLayout top=row();dot=new View(context);LinearLayout.LayoutParams dotLayout=new LinearLayout.LayoutParams(dp(9),dp(9));dotLayout.setMarginEnd(dp(10));top.addView(dot,dotLayout);
        protocol=label(14,false);protocol.setTypeface(Typeface.MONOSPACE);top.addView(protocol);top.setMinimumHeight(dp(26));addView(top,new LayoutParams(-1,-2));
        status=label(18,true);status.setMinimumHeight(dp(38));addView(status,new LayoutParams(-1,-2));divider();
        LinearLayout deviceRow=row();deviceLabel=label(15,false);devices=label(17,true);devices.setTypeface(Typeface.MONOSPACE);
        deviceRow.addView(deviceLabel,new LayoutParams(0,-2,1));deviceRow.addView(devices);deviceRow.setMinimumHeight(dp(50));addView(deviceRow,new LayoutParams(-1,-2));divider();
        LinearLayout traffic=row();receive=label(14,false);transmit=label(14,false);transmit.setGravity(Gravity.END);
        traffic.addView(receive,new LayoutParams(0,-2,1));traffic.addView(transmit,new LayoutParams(0,-2,1));traffic.setMinimumHeight(dp(50));addView(traffic,new LayoutParams(-1,-2));
        detail=label(12,false);detail.setTextColor(context.getColor(R.color.atticus_secondary));addView(detail,new LayoutParams(-1,-2));
    }
    void show(String name,String phase,boolean running,boolean failed,String countLabel,int count,String rx,String tx,String note) {
        update(protocol,name);update(status,phase);
        int color=getContext().getColor(failed?R.color.home_error:running?R.color.home_running:R.color.atticus_secondary);
        if(dotColor!=color){dotColor=color;status.setTextColor(color);GradientDrawable marker=new GradientDrawable();marker.setShape(GradientDrawable.OVAL);marker.setColor(color);dot.setBackground(marker);}
        update(deviceLabel,countLabel);update(devices,Integer.toString(Math.max(0,count)));
        update(receive,getContext().getString(R.string.home_receive,rx));update(transmit,getContext().getString(R.string.home_transmit,tx));
        update(detail,note);detail.setVisibility(note.isEmpty()?GONE:VISIBLE);
    }
    private static void update(TextView target,String value){if(!android.text.TextUtils.equals(target.getText(),value))target.setText(value);}
    private LinearLayout row(){LinearLayout value=new LinearLayout(getContext());value.setOrientation(HORIZONTAL);value.setGravity(Gravity.CENTER_VERTICAL);return value;}
    private TextView label(int sp,boolean bold){TextView value=new TextView(getContext());value.setTextSize(sp);value.setTextColor(getContext().getColor(R.color.black));if(bold)value.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return value;}
    private void divider(){View line=new View(getContext());line.setBackgroundColor(getContext().getColor(R.color.home_card_border));addView(line,new LayoutParams(-1,dp(1)));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
