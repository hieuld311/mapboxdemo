package com.ivi.launcher.model.home;

import androidx.annotation.NonNull;

import com.ivi.launcher.constant.HomeCardItem;

import java.util.ArrayList;

public interface HomeCardDataListener {
    void onHomeCardsChanged(@NonNull ArrayList<HomeCardItem> cards);
}