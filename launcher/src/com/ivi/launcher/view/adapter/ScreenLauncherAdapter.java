package com.ivi.launcher.view.adapter;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;
import java.util.List;


/**
 * The adapter that populates the grid view with apps.
 */
public final class ScreenLauncherAdapter extends FragmentPagerAdapter {

    private final List<Fragment> fragments = new ArrayList<>();

    public ScreenLauncherAdapter(@NonNull FragmentManager fm) {
        super(fm);
    }

    public void addFragment(Fragment fragment) {
        fragments.add(fragment);
        notifyDataSetChanged();
    }

    public void removeFragment(int index) {
        fragments.remove(index);
        notifyDataSetChanged();
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        super.destroyItem(container, position, object);
        if (position <= getCount()) {
            FragmentManager manager = ((Fragment) object).getFragmentManager();
            FragmentTransaction trans = manager.beginTransaction();
            trans.remove((Fragment) object);
            trans.commit();
        }
    }

    @Override
    public int getItemPosition(@NonNull Object object) {
        int index = fragments.indexOf(object);
        return index == -1 ? POSITION_NONE : index;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        return fragments.get(position);
    }

    @Override
    public int getCount() {
        return fragments.size();
    }
}
