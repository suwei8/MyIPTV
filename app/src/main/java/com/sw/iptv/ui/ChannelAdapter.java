package com.sw.iptv.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sw.iptv.R;
import com.sw.iptv.core.Settings;
import com.sw.iptv.model.Channel;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {

    public interface OnClick { void onClick(Channel c, int pos); }

    private final List<Channel> items = new ArrayList<>();
    public OnClick onClick;
    public OnClick onLongClick;
    private Channel playing;
    private final boolean hideNum;

    public ChannelAdapter() {
        this.hideNum = Settings.get().isChannelNumHide();
    }

    public void setItems(List<Channel> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setPlaying(Channel c) {
        playing = c;
        notifyDataSetChanged();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView num, name, fav;
        Holder(View v) {
            super(v);
            num = v.findViewById(R.id.num);
            name = v.findViewById(R.id.name);
            fav = v.findViewById(R.id.fav);
            v.setFocusable(true);
            v.setBackgroundResource(R.drawable.bg_selected);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_channel, p, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        Channel c = items.get(pos);
        h.num.setVisibility(hideNum ? View.GONE : View.VISIBLE);
        h.num.setText(c.id);
        h.name.setText(c.displayName());
        h.fav.setText(c.favored ? "★" : "");
        h.name.setTextColor(c == playing ? 0xFF4FC3F7 : 0xFFFFFFFF);
        h.itemView.setOnClickListener(v -> { if (onClick != null) onClick.onClick(c, pos); });
        h.itemView.setOnLongClickListener(v -> {
            if (onLongClick != null) { onLongClick.onClick(c, pos); return true; }
            return false;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }
}
