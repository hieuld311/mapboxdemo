package com.ivi.launcher.view.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ivi.launcher.R;
import com.ivi.launcher.constant.HomeCardItem;
import com.ivi.launcher.constant.HomeCarouselConfig;
import com.ivi.launcher.view.viewholder.HomeCardViewHolder;

import java.util.ArrayList;
import java.util.List;

public class HomeCarouselAdapter extends RecyclerView.Adapter<HomeCardViewHolder> {
    public static final String PAYLOAD_FOCUS_CHANGED = "payload_focus_changed";

    public interface CarouselHost {
        int getLoopStartPosition(int realIndex);

        void cancelPendingFocusAnimation();

        void scheduleFocusFromCurrentCenterIfIdle(@NonNull String reason);

        int dpToPx(int dp);

        void logFocus(String message);

        void logFocusWarn(String message);

        String scrollStateToString(int state);

        String adapterPositionInfo(int adapterPosition);

        void beginFocusTransitionAnimator();

        void finishFocusTransitionAnimator();

        boolean isFocusTransitionLocked();

        int getCarouselScrollState();

        void stopCarouselScroll();

        void scrollCarouselBy(int dx);

        void onHomeCardClicked(@NonNull HomeCardItem item, int adapterPosition);

        void onFocusAnimationCompleted(int adapterPosition);

        void onMediaPlayPauseClicked(@NonNull HomeCardItem item, int adapterPosition);

        void onMediaPreviousClicked(@NonNull HomeCardItem item, int adapterPosition);

        void onMediaNextClicked(@NonNull HomeCardItem item, int adapterPosition);
    }

    private final ArrayList<HomeCardItem> items;
    private final CarouselHost host;
    private int focusedPosition;

    public HomeCarouselAdapter(@NonNull ArrayList<HomeCardItem> items, @NonNull CarouselHost host) {
        this.items = items;
        this.host = host;
        this.focusedPosition = host.getLoopStartPosition(HomeCarouselConfig.INITIAL_REAL_FOCUS_INDEX);
    }

    public void setItems(@NonNull List<HomeCardItem> newItems) {
        ArrayList<HomeCardItem> snapshot = new ArrayList<>();

        for (HomeCardItem item : newItems) {
            if (item != null) {
                snapshot.add(item.copy());
            }
        }

        items.clear();
        items.addAll(snapshot);

        if (items.isEmpty()) {
            focusedPosition = RecyclerView.NO_POSITION;
        } else if (focusedPosition == RecyclerView.NO_POSITION) {
            focusedPosition = host.getLoopStartPosition(HomeCarouselConfig.INITIAL_REAL_FOCUS_INDEX);
        }

        notifyDataSetChanged();
    }
    public void setFocusedPosition(int position, boolean animate) {
        if (items == null || items.isEmpty()) {
            host.logFocusWarn("Adapter.setFocusedPosition ignored | items empty");
            return;
        }

        if (position < 0 || position >= getItemCount()) {
            host.logFocusWarn("Adapter.setFocusedPosition ignored | invalid position="
                    + host.adapterPositionInfo(position));
            return;
        }

        if (position == focusedPosition) {
            if (!animate) {
                host.logFocus("Adapter.setFocusedPosition force rebind same focus | position="
                        + host.adapterPositionInfo(position));
                notifyDataSetChanged();
            } else {
                host.logFocus("Adapter.setFocusedPosition ignored same animated focus | position="
                        + host.adapterPositionInfo(position));
            }
            return;
        }

        int oldPosition = focusedPosition;
        focusedPosition = position;

        if (animate) {
            host.cancelPendingFocusAnimation();
            host.stopCarouselScroll();

            if (oldPosition != RecyclerView.NO_POSITION) {
                notifyItemChanged(oldPosition, PAYLOAD_FOCUS_CHANGED);
            }

            notifyItemChanged(focusedPosition, PAYLOAD_FOCUS_CHANGED);
        } else {
            notifyDataSetChanged();
        }
    }
    public void clearFocusOnUserDrag(boolean animateCollapse) {
        if (focusedPosition == RecyclerView.NO_POSITION) {
            return;
        }

        int oldPosition = focusedPosition;
        focusedPosition = RecyclerView.NO_POSITION;

        if (animateCollapse) {
            notifyItemChanged(oldPosition, PAYLOAD_FOCUS_CHANGED);
        } else {
            notifyItemChanged(oldPosition);
        }
    }

    public String getFocusedPositionInfo() {
        return host.adapterPositionInfo(focusedPosition);
    }

    @NonNull
    @Override
    public HomeCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_carousel_card, parent, false);
        return new HomeCardViewHolder(view, host);
    }

    @Override
    public void onBindViewHolder(@NonNull HomeCardViewHolder holder, int position) {
        int realPosition = getRealPosition(position);
        HomeCardItem item = items.get(realPosition);
        holder.bind(item, position, position == focusedPosition, false);
        holder.itemView.setOnClickListener(v ->
                host.onHomeCardClicked(item, holder.getBindingAdapterPosition()));
    }

    @Override
    public void onBindViewHolder(
            @NonNull HomeCardViewHolder holder,
            int position,
            @NonNull List<Object> payloads
    ) {
        int realPosition = getRealPosition(position);
        HomeCardItem item = items.get(realPosition);
        holder.bind(item, position, position == focusedPosition, !payloads.isEmpty());
        holder.itemView.setOnClickListener(v ->
                host.onHomeCardClicked(item, holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        if (items == null || items.isEmpty()) {
            return 0;
        }

        return HomeCarouselConfig.LOOP_ITEM_COUNT;
    }

    private int getRealPosition(int adapterPosition) {
        int size = items.size();
        if (size == 0) {
            return 0;
        }

        int realPosition = adapterPosition % size;
        if (realPosition < 0) {
            realPosition += size;
        }

        return realPosition;
    }
}