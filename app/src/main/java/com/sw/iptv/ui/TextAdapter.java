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
    private RecyclerView recyclerView;
    public OnClick<T> onClick;
    public OnClick<T> onLongClick;
    public OnClick<T> onFocus;

    public TextAdapter(Function<T, String> labeler) { this.labeler = labeler; }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        recyclerView = rv;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        super.onDetachedFromRecyclerView(rv);
        recyclerView = null;
    }

    public void setItems(List<T> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setSelected(int pos) {
        int old = selected;
        selected = pos;
        notifyItemChangedSafe(old);
        notifyItemChangedSafe(pos);
    }

    /**
     * 安全刷新单行:焦点切换(onFocusChange)常发生在 RecyclerView 布局/滚动过程中,
     * 此时直接 notifyItemChanged 会抛
     * "Cannot call this method while RecyclerView is computing a layout or scrolling" 导致崩溃。
     * 改为 post 到下一帧执行即可避开布局/滚动;选中态字段已同步更新,
     * 延迟的 rebind 读到的仍是最新 selected,不会错位。
     */
    private void notifyItemChangedSafe(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        final int p = pos;
        RecyclerView rv = recyclerView;
        if (rv == null) {
            notifyItemChanged(p);
        } else {
            rv.post(() -> {
                if (p < items.size()) notifyItemChanged(p);
            });
        }
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
        h.itemView.setOnFocusChangeListener((v, has) -> {
            if (has && onFocus != null) onFocus.onClick(item, pos);
        });
        h.itemView.setOnClickListener(v -> { if (onClick != null) onClick.onClick(item, pos); });
        h.itemView.setOnLongClickListener(v -> {
            if (onLongClick != null) { onLongClick.onClick(item, pos); return true; }
            return false;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }
}
