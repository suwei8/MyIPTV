package com.sw.iptv.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sw.iptv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 通用单文本行适配器(分类列表/设置项复用)。 */
public class TextAdapter<T> extends RecyclerView.Adapter<TextAdapter.Holder> {

    public interface OnClick<T> { void onClick(T item, int pos); }

    private final List<T> items = new ArrayList<>();
    private final Function<T, String> labeler;
    private int selected = -1;
    public OnClick<T> onClick;
    public OnClick<T> onLongClick;

    public TextAdapter(Function<T, String> labeler) { this.labeler = labeler; }

    public void setItems(List<T> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setSelected(int pos) {
        int old = selected;
        selected = pos;
        if (old >= 0) notifyItemChanged(old);
        if (pos >= 0) notifyItemChanged(pos);
    }

    public int getSelected() { return selected; }

    static class Holder extends RecyclerView.ViewHolder {
        TextView tv;
        Holder(View v) {
            super(v);
            tv = v.findViewById(R.id.text);
            v.setFocusable(true);
            v.setBackgroundResource(R.drawable.bg_selected);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_text, p, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        T item = items.get(pos);
        h.tv.setText(labeler.apply(item));
        h.tv.setTextColor(pos == selected ? 0xFF90CAF9 : 0xFFD0FFFF & 0xFFFFFFFF);
        h.itemView.setOnClickListener(v -> { if (onClick != null) onClick.onClick(item, pos); });
        h.itemView.setOnLongClickListener(v -> {
            if (onLongClick != null) { onLongClick.onClick(item, pos); return true; }
            return false;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }
}
