package com.ivi.car.navigation.util

import android.content.Context
import android.content.SharedPreferences

object SharePreferences {
    /**
     * getPrefs
     *
     * @param context
     * @return
     */
    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(Constant.KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
    }

    /**
     * load preferences
     *
     * @param sharedPreferences
     * @return
     */
    fun getIntPreferences(sharedPreferences: SharedPreferences, id: String): Int {
        return sharedPreferences.getInt(id, 1)
    }

    /**
     * save preferences
     *
     * @param editor
     * @param value
     */
    fun saveIntPreferences(editor: SharedPreferences.Editor, id: String, value: Int) {
        editor.putInt(id, value)
        editor.apply()
    }
}