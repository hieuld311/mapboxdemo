LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
        $(call all-Iaidl-files-under, src)
LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/src
LOCAL_MODULE := com.fauto.car.plugin.navigation
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_MODULE_TAGS := optional
LOCAL_STATIC_JAVA_LIBRARIES := navigation-lib gson-2.9.0 converter-gson-2.9.0 okhttp-3.14.9 okio-1.17.2 retrofit-2.9.0
LOCAL_JAVA_LIBRARIES := \
        fauto.car.plugin.navigation\
	android.car\
	com.fauto.car.base
include $(BUILD_FAUTO_CAR_SERVICE_PLUGIN)
